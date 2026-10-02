package fnord.dedup.cross

import fnord.dedup.StopToken
import fnord.dedup.store.DuckStore
import org.duckdb.DuckDBAppender
import java.nio.file.Path

/** Session-only selection/candidates/groups; the only persistent writes are completed hashes. */
final class CrossScanStore implements AutoCloseable {
    final DuckStore db
    private final String prefix = 'cross_' + UUID.randomUUID().toString().replace('-', '')
    private final List<String> tables = []
    List<Map> selected = []

    CrossScanStore(DuckStore db) { this.db = db }
    private String table(String suffix) { prefix + '_' + suffix }
    private String create(String suffix, String definition) {
        String name = table(suffix)
        db.exec("CREATE TEMP TABLE ${name} ${definition}")
        tables.add(name)
        name
    }

    void select(List<String> names, StopToken stop) {
        if (names == null || names.size() < 2) throw new IllegalArgumentException('Select at least two distinct scan names')
        if (names.any { !(it instanceof String) || !it.trim() }) throw new IllegalArgumentException('Scan names must be nonblank strings')
        if (names.toSet().size() != names.size()) throw new IllegalArgumentException('Repeated --scan names are not allowed')
        List<String> unknown = []
        names.each { String name ->
            stop.check()
            def rows = db.rows('SELECT * FROM scans WHERE name=?', name)
            if (rows.empty) unknown.add(name)
            else selected.add(new LinkedHashMap(rows[0]))
        }
        if (!unknown.empty) throw new IllegalArgumentException('Unknown scans: ' + groovy.json.JsonOutput.toJson(unknown))
        for (Map scan : selected) {
            stop.check()
            if (!(scan.phase in ['READY','HASHING','COMPLETE','COMPLETE_WITH_ERRORS'])) {
                throw new IllegalStateException('Finish discovery before cross-scan verification: ' + scan.name)
            }
            if (scan.algorithm != 'SHA-256') throw new IllegalArgumentException('Selected scans must all use SHA-256: ' + scan.name)
            if (scan.active_dir != null || !db.rows('SELECT 1 FROM directories WHERE scan_id=? AND NOT completed LIMIT 1', scan.scan_id).empty) {
                throw new IllegalStateException('Unfinished discovery checkpoint: ' + scan.name)
            }
            Map errors = db.rows('''SELECT count(*) AS errors,
                count(*) FILTER (WHERE phase='DISCOVERY') AS discovery_errors FROM scan_errors WHERE scan_id=?''', scan.scan_id)[0]
            scan.prior_errors = errors.errors
            scan.discovery_errors = errors.discovery_errors
        }
        String name = create('selected', '(ordinal INTEGER, scan_id BIGINT PRIMARY KEY, scan_name VARCHAR, scan_root VARCHAR)')
        selected.eachWithIndex { Map scan, int ordinal ->
            db.exec("INSERT INTO ${name} VALUES (?,?,?,?)", ordinal, scan.scan_id, scan.name, scan.root)
        }
        // Fail before reading any source if persistent keys/digests are ambiguous.
        if (!db.rows("""SELECT e.scan_id,e.entry_id FROM entries e JOIN ${name} s USING(scan_id)
            GROUP BY e.scan_id,e.entry_id HAVING count(*)<>1 LIMIT 1""").empty) {
            throw new IllegalStateException('Selected inventory has duplicate entry IDs; audit the database')
        }
        if (!db.rows("""SELECT h.scan_id,h.entry_id FROM hashes h JOIN ${name} s USING(scan_id)
            GROUP BY h.scan_id,h.entry_id HAVING count(*)<>1 LIMIT 1""").empty) {
            throw new IllegalStateException('Selected inventory has duplicate hash keys; audit the database')
        }
        if (!db.rows("""SELECT 1 FROM hashes h JOIN ${name} s USING(scan_id)
            LEFT JOIN entries e USING(scan_id,entry_id)
            WHERE e.entry_id IS NULL OR e.kind<>'FILE' OR NOT regexp_full_match(h.sha256,'[0-9a-f]{64}') LIMIT 1""").empty) {
            throw new IllegalStateException('Selected inventory has invalid hashes; audit the database')
        }
    }

    Map prepare(StopToken stop) {
        stop.check()
        String selectedTable = table('selected')
        String sizes = create('sizes', """AS SELECT e.size FROM entries e JOIN ${selectedTable} s USING(scan_id)
            WHERE e.kind='FILE' GROUP BY e.size HAVING count(DISTINCT e.scan_id)>=2""")
        stop.check()
        String files = create('files', """AS SELECT row_number() OVER (ORDER BY s.ordinal,e.entry_id) AS sequence,
            s.ordinal AS scan_ordinal,s.scan_name,s.scan_root,e.scan_id,e.entry_id,e.relative_path,e.filename,
            e.size,e.modified_sec,e.modified_nano,h.sha256 AS existing_sha256
            FROM entries e JOIN ${selectedTable} s USING(scan_id) JOIN ${sizes} z USING(size)
            LEFT JOIN hashes h USING(scan_id,entry_id) WHERE e.kind='FILE'""")
        stop.check()
        create('pending', "AS SELECT * FROM ${files} WHERE existing_sha256 IS NULL")
        create('incoming', '(scan_id BIGINT, entry_id BIGINT, sha256 VARCHAR NOT NULL, PRIMARY KEY(scan_id,entry_id))')
        Map counts = db.rows("""SELECT count(*) AS candidate_files,
            count(existing_sha256) AS existing_candidate_hashes,
            count(*) FILTER (WHERE existing_sha256 IS NULL) AS hashes_needed FROM ${files}""")[0]
        counts.candidate_sizes = db.rows("SELECT count(*) AS n FROM ${sizes}")[0].n
        counts
    }

    List<Map> page(long after, int limit) {
        db.rows("SELECT * FROM ${table('pending')} WHERE sequence>? ORDER BY sequence LIMIT ?", after, limit)
    }

    /** One explicit Appender transaction per bounded batch; never edits scan state/errors. */
    long saveHashes(List<Map> values) {
        if (values.empty) return 0L
        String incoming = table('incoming')
        db.transaction {
            // SQL activates DuckDB JDBC's lazy native transaction BEFORE appender use.
            db.exec("DELETE FROM ${incoming}")
            db.connection.createAppender('temp', 'main', incoming).withCloseable { DuckDBAppender a ->
                for (Map value : values) {
                    if (!(value.sha256 ==~ /[0-9a-f]{64}/)) throw new IllegalArgumentException('Invalid completed SHA-256')
                    a.beginRow(); a.append(value.scan_id as long); a.append(value.entry_id as long)
                    a.append(value.sha256 as String); a.endRow()
                }
            }
            if (!db.rows("""SELECT 1 FROM ${incoming} i LEFT JOIN ${table('pending')} p USING(scan_id,entry_id)
                WHERE p.entry_id IS NULL LIMIT 1""").empty) throw new IllegalStateException('Hash outside selected unresolved candidates')
            if (!db.rows("""SELECT 1 FROM ${incoming} i JOIN hashes h USING(scan_id,entry_id)
                WHERE i.sha256<>h.sha256 LIMIT 1""").empty) throw new IllegalStateException('Conflicting completed hash; no overwrite allowed')
            long count = db.rows("""SELECT count(*) AS n FROM ${incoming} i WHERE NOT EXISTS
                (SELECT 1 FROM hashes h WHERE h.scan_id=i.scan_id AND h.entry_id=i.entry_id)""")[0].n as long
            db.exec("""INSERT INTO hashes(scan_id,entry_id,sha256) SELECT i.scan_id,i.entry_id,i.sha256 FROM ${incoming} i
                WHERE NOT EXISTS (SELECT 1 FROM hashes h WHERE h.scan_id=i.scan_id AND h.entry_id=i.entry_id)""")
            count
        } as long
    }

    Map groups(StopToken stop) {
        stop.check()
        String groups = create('groups', """AS SELECT f.size,h.sha256,count(*) AS copies,count(DISTINCT f.scan_id) AS scan_count
            FROM ${table('files')} f JOIN hashes h USING(scan_id,entry_id)
            GROUP BY f.size,h.sha256 HAVING count(DISTINCT f.scan_id)>=2""")
        db.rows("SELECT count(*) AS duplicate_groups,coalesce(sum(copies::HUGEINT),0) AS duplicate_observations FROM ${groups}")[0]
    }

    void eachDuplicate(StopToken stop, Closure consumer) {
        db.eachRow("""SELECT f.scan_id,f.scan_name,f.scan_root,f.entry_id,f.relative_path,f.filename,f.size,
            f.modified_sec,f.modified_nano,h.sha256,g.copies,g.scan_count
            FROM ${table('files')} f JOIN hashes h USING(scan_id,entry_id)
            JOIN ${table('groups')} g ON g.size=f.size AND g.sha256=h.sha256
            ORDER BY f.size DESC,h.sha256,f.scan_ordinal,f.relative_path,f.entry_id""", new Object[0]) { Map row ->
            stop.check()
            row.path = Path.of(row.scan_root as String).resolve(row.relative_path as String).toString()
            row.group_id = row.size.toString() + ':' + row.sha256
            consumer.call(row)
        }
    }

    @Override void close() {
        Exception failure = null
        for (String name : tables.reverse()) {
            try { db.exec("DROP TABLE IF EXISTS ${name}") }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e) }
        }
        if (failure != null) throw failure
    }
}
