package fnord.dedup.image

import fnord.dedup.store.DuckStore

class ImageStore {
    final DuckStore db
    final String instanceId
    ImageStore(DuckStore db) {
        this.db=db
        if (db.rows("SELECT 1 FROM information_schema.tables WHERE table_schema='main' AND table_name='image_schema_info'").empty) {
            db.transaction {
                String schema=getClass().getResourceAsStream('/image/schema.sql').withCloseable { it.getText('UTF-8') }
                schema.split(';').findAll { it.trim() }.each { db.exec(it) }
                db.exec('INSERT INTO image_schema_info VALUES (1,?)',UUID.randomUUID().toString())
            }
        }
        def version=db.rows('SELECT * FROM image_schema_info')
        if (version.size()!=1 || version[0].version!=1) throw new IllegalArgumentException('Unsupported image schema version')
        instanceId=version[0].instance_id as String
    }
    void recover() {
        db.transaction {
            for (String table : ImageBatch.TABLES) db.exec("DELETE FROM ${table} WHERE result_id IN (SELECT result_id FROM image_results WHERE state='RUNNING')")
            db.exec("DELETE FROM image_results WHERE state='RUNNING'")
            db.exec("UPDATE image_jobs SET state='PENDING',result_id=NULL,duplicate=false WHERE state='RUNNING'")
        }
    }
    void identify(Map scan) {
        if (scan.phase=='DISCOVERING') throw new IllegalStateException('Finish filesystem discovery before image analysis')
        db.transaction {
            db.exec("INSERT INTO image_runs VALUES (?,'IDENTIFYING',current_timestamp) ON CONFLICT(scan_id) DO UPDATE SET phase='IDENTIFYING',updated_at=now()",scan.scan_id)
            // One vectorized selection: no per-file INSERT/commit or whole-tree Java list.
            db.exec('''INSERT INTO image_jobs(scan_id,source_id,relative_path,format,size,modified_sec,modified_nano,state)
                SELECT e.scan_id,e.entry_id,e.relative_path,
                CASE lower(regexp_extract(e.filename,'[^.]+$')) WHEN 'vhd' THEN 'vpc'
                WHEN 'img' THEN 'raw' WHEN 'dd' THEN 'raw' WHEN 'iso' THEN 'raw'
                ELSE lower(regexp_extract(e.filename,'[^.]+$')) END,
                e.size,e.modified_sec,e.modified_nano,'PENDING'
                FROM entries e WHERE e.scan_id=? AND e.kind='FILE'
                AND regexp_matches(lower(e.filename),'\\.(vmdk|qcow|qcow2|vdi|vhd|vhdx|vpc|qed|img|raw|dd|iso|dmg)$')
                AND NOT EXISTS (SELECT 1 FROM image_jobs j WHERE j.scan_id=e.scan_id AND j.source_id=e.entry_id)''',scan.scan_id)
            db.exec("UPDATE image_runs SET phase='ANALYZING',updated_at=now() WHERE scan_id=?",scan.scan_id)
        }
    }
    Map status(String name) {
        Map scan=db.scan(name)
        Map run=db.rows('SELECT phase FROM image_runs WHERE scan_id=?',scan.scan_id).find() ?: [phase:'NOT_STARTED']
        Map result=[scan_id:scan.scan_id,name:name,phase:run.phase]
        result.putAll(db.rows('''SELECT count(*) AS candidates,
          count(*) FILTER (WHERE state='COMPLETE') AS complete,
          count(*) FILTER (WHERE state='PARTIAL') AS partial,
          count(*) FILTER (WHERE state='PENDING' OR state='RUNNING') AS pending,
          count(*) FILTER (WHERE state='COMPONENT') AS component_files,
          count(*) FILTER (WHERE duplicate) AS duplicates,
          count(*) FILTER (WHERE state='PARTIAL' OR diagnostic IS NOT NULL) AS errors
          FROM image_jobs WHERE scan_id=?''',scan.scan_id)[0])
        result.putAll(db.rows('''SELECT count(*) AS entries,count(*) FILTER (WHERE sha256 IS NOT NULL) AS hashes_completed,
          coalesce(sum(actual_size::HUGEINT) FILTER (WHERE sha256 IS NOT NULL),0) AS file_bytes
          FROM image_entries WHERE result_id IN (SELECT j.result_id FROM image_jobs j JOIN image_results r USING(result_id)
          WHERE j.scan_id=? AND r.state IN ('COMPLETE','PARTIAL'))''',scan.scan_id)[0])
        result
    }
    void finalized(String result) {
        def rows=db.rows("SELECT state FROM image_results WHERE result_id=? AND state IN ('COMPLETE','PARTIAL')",result)
        if (rows.empty) throw new IllegalArgumentException('Unknown or unfinished image result')
    }
    void eachImage(String name,Closure callback) {
        db.eachRow('''SELECT j.*,r.fingerprint,r.virtual_size,r.provider,r.summary_json FROM image_jobs j
          LEFT JOIN image_results r ON j.result_id=r.result_id AND r.state IN ('COMPLETE','PARTIAL')
          WHERE j.scan_id=? ORDER BY j.source_id''',[db.scan(name).scan_id] as Object[],callback)
    }
    void eachResult(String table,String result,Closure callback) {
        if (!ImageBatch.TABLES.contains(table)) throw new IllegalArgumentException('Unknown image table')
        finalized(result)
        db.eachRow("SELECT * FROM ${table} WHERE result_id=?",[result] as Object[],callback)
    }
    void eachError(String name,Closure callback) {
        long id=db.scan(name).scan_id as long
        db.eachRow('''SELECT j.relative_path,j.result_id,e.filesystem_id,e.entry_id,e.category,e.code,e.message
           FROM image_jobs j JOIN image_results r ON j.result_id=r.result_id JOIN image_errors e ON e.result_id=r.result_id
           WHERE j.scan_id=? AND r.state IN ('COMPLETE','PARTIAL')
           UNION ALL SELECT relative_path,NULL,NULL,NULL,'OPERATIONAL','JOB_FAILED',diagnostic FROM image_jobs
           WHERE scan_id=? AND diagnostic IS NOT NULL''',[id,id] as Object[],callback)
    }
}
