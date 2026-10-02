package fnord.dedup.archive

import fnord.dedup.StopToken
import fnord.dedup.store.DuckStore

/** Archive control tables and streaming views over the published result DAG. */
class ArchiveStore {
    final DuckStore db
    final String instanceId
    final int batchSize

    ArchiveStore(DuckStore db, int batchSize) {
        this.db = db; this.batchSize = batchSize
        def tables = db.rows("SELECT table_name FROM information_schema.tables WHERE table_schema='main'")*.table_name
        if (!tables.contains('archive_schema_info')) {
            if (tables.any { it.startsWith('archive_') }) throw new IllegalArgumentException('Unrecognized existing archive tables')
            String schema = getClass().getResourceAsStream('/archive/schema.sql').withCloseable { it.getText('UTF-8') }
            db.transaction {
                schema.split(';').findAll { it.trim() }.each { db.exec(it) }
                db.exec('INSERT INTO archive_schema_info VALUES (1,?)', UUID.randomUUID().toString())
            }
        }
        def version = db.rows('SELECT * FROM archive_schema_info')
        if (version.size() != 1 || version[0].version != 1) throw new IllegalArgumentException('Unsupported archive feature schema')
        instanceId = version[0].instance_id as String
    }

    ArchiveBatch batch() { new ArchiveBatch(db, batchSize) }

    void identify(Map scan, StopToken stop) {
        long id = scan.scan_id as long
        def prior = db.rows('SELECT phase FROM archive_runs WHERE scan_id=?', id)
        if (!prior.empty && prior[0].phase != 'IDENTIFYING') return
        db.transaction {
            db.exec('DELETE FROM archive_inputs WHERE scan_id=?', id)
            db.exec('DELETE FROM archive_jobs WHERE scan_id=?', id)
            db.exec("INSERT INTO archive_runs(scan_id,phase) VALUES (?,'IDENTIFYING') ON CONFLICT(scan_id) DO UPDATE SET phase='IDENTIFYING',updated_at=now()", id)
        }
        db.exec('''CREATE OR REPLACE TEMP TABLE archive_candidates AS
            SELECT row_number() OVER (ORDER BY entry_id) AS seq, * FROM entries
            WHERE scan_id=? AND kind='FILE' AND regexp_matches(filename,?)''', id, ArchiveNames.SQL_PATTERN)
        long after = 0
        ArchiveBatch writer = batch()
        while (true) {
            stop.check()
            List<Map> page = db.rows('SELECT * FROM archive_candidates WHERE seq>? AND seq<=? ORDER BY seq', after, after + batchSize)
            if (page.empty) break
            for (Map row : page) {
                stop.check()
                Map info = ArchiveNames.describe(row.relative_path as String)
                if (info != null) writer.add('archive_inputs', [id,info.group_key,info.flavor,info.slot,
                    row.entry_id,row.relative_path,row.size,row.modified_sec,row.modified_nano])
                after = row.seq as long
            }
        }
        writer.flush()
        db.transaction {
            db.exec('''INSERT INTO archive_jobs(scan_id,group_key,first_entry,flavor,status)
                SELECT scan_id,group_key,min(source_id),min(flavor),'PENDING' FROM archive_inputs
                WHERE scan_id=? GROUP BY scan_id,group_key''', id)
            db.exec("UPDATE archive_runs SET phase='ANALYZING',updated_at=current_timestamp WHERE scan_id=?", id)
        }
    }

    void recover() {
        // Parent attempts can reference completed children. Retain those immutable
        // children as caches while deleting only unfinished attempts' staging rows.
        db.transaction {
            for (String table : ['archive_members','archive_errors','archive_volumes']) {
                db.exec("DELETE FROM ${table} WHERE result_id IN (SELECT result_id FROM archive_results WHERE state='RUNNING')")
            }
            db.exec("DELETE FROM archive_nested WHERE parent_result_id IN (SELECT result_id FROM archive_results WHERE state='RUNNING')")
            db.exec("DELETE FROM archive_results WHERE state='RUNNING'")
            db.exec("UPDATE archive_jobs SET status='PENDING',result_id=NULL,duplicate=false WHERE status='RUNNING'")
        }
    }

    Map result(String id) {
        def rows = db.rows('SELECT * FROM archive_results WHERE result_id=?', id)
        if (rows.empty) throw new IllegalArgumentException('Unknown archive result: ' + id)
        rows[0]
    }

    private static String reachable() {
        '''WITH RECURSIVE reachable(result_id) AS (
             SELECT result_id FROM archive_jobs WHERE scan_id=? AND result_id IS NOT NULL
             UNION
             SELECT n.child_result_id FROM archive_nested n JOIN reachable r ON n.parent_result_id=r.result_id
           ) '''
    }

    Map status(String name) {
        long id = db.scan(name).scan_id as long
        def run = db.rows('SELECT phase,updated_at FROM archive_runs WHERE scan_id=?', id)
        Map answer = [scan_id:id, name:name, phase:run.empty ? 'NOT_STARTED' : run[0].phase]
        answer.putAll(db.rows('''SELECT count(*) AS root_archives,
             count(*) FILTER (WHERE status='COMPLETE') AS completed,
             count(*) FILTER (WHERE status='PARTIAL') AS with_errors,
             count(*) FILTER (WHERE status IN ('PENDING','RUNNING')) AS pending,
             count(*) FILTER (WHERE duplicate) AS duplicate_roots,
             count(*) FILTER (WHERE retryable) AS retryable_roots
             FROM archive_jobs WHERE scan_id=?''', id)[0])
        answer.putAll(db.rows(reachable() + '''SELECT
             count(*) AS indexed_members,
             count(*) FILTER (WHERE sha256 IS NOT NULL AND integrity='READ_OK') AS checksummed_members,
             count(*) FILTER (WHERE integrity='DAMAGED') AS damaged_members,
             coalesce(sum(actual_size::HUGEINT),0) AS recovered_bytes
             FROM archive_members m JOIN reachable r USING(result_id)
             JOIN archive_results a USING(result_id) WHERE a.state IN ('COMPLETE','PARTIAL')''', id)[0])
        answer.errors = db.rows(reachable() + '''SELECT
             (SELECT count(*) FROM archive_errors e JOIN reachable r USING(result_id)) +
             (SELECT count(*) FROM archive_jobs WHERE scan_id=? AND diagnostic IS NOT NULL) AS n''', id, id)[0].n
        answer.nested_archives = db.rows(reachable() + 'SELECT count(*) AS n FROM archive_nested n JOIN reachable r ON n.parent_result_id=r.result_id', id)[0].n
        answer
    }

    void eachArchive(String name, Closure consumer) {
        long id = db.scan(name).scan_id as long
        db.eachRow(reachable() + '''SELECT 'ROOT' AS location_kind,j.group_key AS source,
             NULL::VARCHAR AS parent_result_id,j.result_id,j.status,j.duplicate,j.diagnostic,
             a.fingerprint,a.provider,a.reusable,a.retryable,a.height
             FROM archive_jobs j LEFT JOIN archive_results a ON a.result_id=j.result_id WHERE j.scan_id=?
             UNION ALL
             SELECT 'NESTED',n.group_key,n.parent_result_id,n.child_result_id,a.state,n.duplicate,NULL,
             a.fingerprint,a.provider,a.reusable,a.retryable,a.height
             FROM archive_nested n JOIN reachable r ON n.parent_result_id=r.result_id
             JOIN archive_results a ON a.result_id=n.child_result_id
             ORDER BY location_kind,source''', [id,id] as Object[], consumer)
    }

    void eachMember(String resultId, Closure consumer) {
        if (!(result(resultId).state in ['COMPLETE','PARTIAL'])) throw new IllegalStateException('Archive attempt is not finalized')
        db.eachRow('SELECT * EXCLUDE(group_key,flavor,volume_slot) FROM archive_members WHERE result_id=? ORDER BY ordinal', [resultId] as Object[], consumer)
    }

    void eachVolume(String resultId, Closure consumer) {
        if (!(result(resultId).state in ['COMPLETE','PARTIAL'])) throw new IllegalStateException('Archive attempt is not finalized')
        db.eachRow('SELECT * FROM archive_volumes WHERE result_id=? ORDER BY ordinal', [resultId] as Object[], consumer)
    }

    void eachError(String name, Closure consumer) {
        long id = db.scan(name).scan_id as long
        db.eachRow(reachable() + '''SELECT e.result_id,e.ordinal,e.category,e.code,e.message
             FROM archive_errors e JOIN reachable r USING(result_id)
             UNION ALL SELECT result_id,NULL,'OPERATIONAL','JOB_ERROR',diagnostic
             FROM archive_jobs WHERE scan_id=? AND diagnostic IS NOT NULL''', [id,id] as Object[], consumer)
    }
}
