package fnord.dedup.merge

import java.sql.DriverManager

/** Strict v1 logical audit. Never opens paths recorded by scans or temp registries. */
class DatabaseAuditor {
    static final List<String> BASE = ['scans','entries','directories','hashes','scan_errors']
    static final List<String> ARCHIVE = ['archive_runs','archive_inputs','archive_jobs','archive_results',
        'archive_volumes','archive_members','archive_nested','archive_errors']
    static final List<String> IMAGE = ['image_runs','image_jobs','image_results','image_components',
        'image_partitions','image_filesystems','image_entries','image_errors']
    static final Map<String,String> RESOURCES = [base:'/schema.sql', archive:'/archive/schema.sql', image:'/image/schema.sql']
    final MergeSql sql
    final String catalog
    final String p
    final Map reference

    DatabaseAuditor(MergeSql sql, String catalog, Map reference) {
        if (!(catalog in ['src','dst'])) throw new IllegalArgumentException('Invalid audit catalog')
        this.sql = sql; this.catalog = catalog; this.p = catalog + '.main.'; this.reference = reference
    }

    static String schema(String feature) {
        DatabaseAuditor.getResourceAsStream(RESOURCES[feature]).withCloseable { it.getText('UTF-8') }
    }

    static Map expectedSchema() {
        DriverManager.getConnection('jdbc:duckdb:').withCloseable { c ->
            MergeSql q = new MergeSql(c)
            RESOURCES.keySet().each { feature ->
                schema(feature).split(';').findAll { it.trim() }.each { q.exec(it) }
            }
            metadata(q, 'memory')
        }
    }

    static Map metadata(MergeSql q, String catalog) {
        [tables:q.rows("SELECT table_name,sql FROM duckdb_tables() WHERE database_name=? AND schema_name='main' ORDER BY table_name",[catalog]),
         columns:q.rows('''SELECT table_name,column_name,column_index,data_type,is_nullable,column_default
            FROM duckdb_columns() WHERE database_name=? AND schema_name='main' ORDER BY table_name,column_index''',[catalog]),
         constraints:q.rows('''SELECT table_name,constraint_type,constraint_text FROM duckdb_constraints()
            WHERE database_name=? AND schema_name='main' ORDER BY table_name,constraint_type,constraint_text''',[catalog]),
         indexes:q.rows('''SELECT table_name,index_name,is_unique,expressions FROM duckdb_indexes()
            WHERE database_name=? AND schema_name='main' ORDER BY table_name,index_name''',[catalog])]
    }

    Set<String> audit() {
        Set<String> features = validateSchema()
        base()
        if ('archive' in features) archive()
        if ('image' in features) image()
        features
    }

    Set<String> validateSchema() {
        def tables = sql.rows('''SELECT table_name,schema_name FROM duckdb_tables()
            WHERE database_name=? ORDER BY table_name''',[catalog])
        Set<String> names = tables*.table_name as Set
        Set<String> features = ['base'] as Set
        if (names.any { it.startsWith('archive_') }) features.add('archive')
        if (names.any { it.startsWith('image_') }) features.add('image')
        Set<String> expected = ['schema_info'] as Set
        expected.addAll(BASE)
        if ('archive' in features) expected.addAll(ARCHIVE + ['archive_schema_info','archive_temp_roots'])
        if ('image' in features) expected.addAll(IMAGE + ['image_schema_info','image_temp_roots'])
        if (names != expected || tables.any { it.schema_name != 'main' })
            refuse('SCHEMA_MISMATCH', 'Missing, extra, or non-main tables', [missing:(expected-names).sort(), extra:(names-expected).sort()])
        // No silent omission of persisted user views, macros, sequences or custom schemas.
        for (String function : ['duckdb_views','duckdb_sequences','duckdb_functions','duckdb_types']) {
            if ((sql.scalar("SELECT count(*) FROM ${function}() WHERE database_name=?" + (function == 'duckdb_sequences' ? '' : ' AND NOT internal'),[catalog]) as long) != 0)
                refuse('SCHEMA_MISMATCH', 'Unsupported persisted objects in ' + function)
        }
        if ((sql.scalar("SELECT count(*) FROM duckdb_schemas() WHERE database_name=? AND schema_name<>'main' AND NOT internal",[catalog]) as long) != 0)
            refuse('SCHEMA_MISMATCH','Unsupported user schemas')
        Map actual = metadata(sql, catalog)
        for (String part : ['tables','columns','constraints','indexes']) {
            List expectedPart = reference[part].findAll { it.table_name in expected }
            if (actual[part] != expectedPart) refuse('SCHEMA_MISMATCH','Column, constraint, default or index definitions differ: ' + part)
        }
        for (String feature : features) {
            String table = feature == 'base' ? 'schema_info' : feature + '_schema_info'
            List<Map> versions = sql.rows("SELECT * FROM ${p}${table} LIMIT 2")
            if (versions.size() != 1 || versions[0].version != 1) refuse('SCHEMA_VERSION','Expected exactly one supported version in ' + table)
            if (feature != 'base' && !(versions[0].instance_id ==~ /[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/))
                refuse('SCHEMA_MISMATCH','Invalid feature instance ID: ' + feature)
        }
        features
    }

    private void refuse(String code, String message, Map details = [:]) {
        throw new DatabaseMergeException(code, catalog + ': ' + message, code.startsWith('SCHEMA') ? 3 : 4, details)
    }
    private void none(String rule, String query) {
        // NULL and empty are different. Queries must explicitly test required logical fields.
        if (!sql.rows("SELECT * FROM (${query}) audit_failure LIMIT 1").empty) refuse('INCONSISTENT_DATABASE',rule)
    }
    private void unique(String table, String keys) {
        none('Duplicate identity in ' + table, "SELECT ${keys} FROM ${p}${table} GROUP BY ${keys} HAVING count(*)<>1")
    }
    private void orphan(String table, String column, String parent, String key='scan_id') {
        none('Dangling ' + table + '.' + column, "SELECT 1 FROM ${p}${table} t LEFT JOIN ${p}${parent} r ON t.${column}=r.${key} WHERE t.${column} IS NULL OR r.${key} IS NULL")
    }
    private void states(String table, String column, String allowed) {
        none('Unknown or unfinished state in ' + table, "SELECT 1 FROM ${p}${table} WHERE ${column} IS NULL OR ${column} NOT IN (${allowed})")
    }
    private void hashColumn(String table, String column) {
        none('Invalid SHA-256 in ' + table + '.' + column, "SELECT 1 FROM ${p}${table} WHERE ${column} IS NOT NULL AND NOT regexp_full_match(${column},'[0-9a-f]{64}')")
    }

    private void resultIds(String table) {
        none('Invalid result UUID in '+table, "SELECT 1 FROM ${p}${table} WHERE NOT regexp_full_match(result_id,'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}')")
    }
    private void timestamps(String table,String column='modified_nano') {
        none('Invalid nanosecond adjustment in '+table, "SELECT 1 FROM ${p}${table} WHERE ${column} IS NOT NULL AND (${column}<0 OR ${column}>=1000000000)")
    }

    private void base() {
        unique('scans','scan_id'); unique('scans','name')
        states('scans','phase',"'DISCOVERING','READY','HASHING','COMPLETE','COMPLETE_WITH_ERRORS'")
        none('Invalid scan identity, name, root or algorithm', "SELECT 1 FROM ${p}scans WHERE scan_id<=0 OR length(trim(name))=0 OR length(name)>200 OR algorithm<>'SHA-256' OR NOT starts_with(root,'/') OR next_entry_id<2")
        for (String table : BASE - ['scans']) orphan(table,'scan_id','scans')
        unique('entries','scan_id,entry_id'); unique('entries','scan_id,relative_path')
        unique('directories','scan_id,entry_id'); unique('hashes','scan_id,entry_id')
        states('entries','kind',"'DIRECTORY','FILE','SYMLINK','OTHER'")
        none('Invalid entry ID, size, time or relative path', """SELECT 1 FROM ${p}entries WHERE entry_id<1 OR size<0 OR modified_nano<0 OR modified_nano>=1000000000
            OR (entry_id=1 AND (parent_id<>0 OR relative_path<>'' OR kind<>'DIRECTORY'))
            OR (entry_id<>1 AND (parent_id<1 OR parent_id>=entry_id OR relative_path='' OR starts_with(relative_path,'/')
                OR regexp_matches(relative_path,'(^|/)(\\.\\.?)(/|\$)') OR contains(relative_path,'//') OR ends_with(relative_path,'/')))""")
        none('Missing root directory', "SELECT 1 FROM ${p}scans s LEFT JOIN ${p}entries e ON e.scan_id=s.scan_id AND e.entry_id=1 WHERE e.entry_id IS NULL")
        none('Dangling parent or inconsistent path', """SELECT 1 FROM ${p}entries e LEFT JOIN ${p}entries parent
            ON parent.scan_id=e.scan_id AND parent.entry_id=e.parent_id WHERE e.entry_id<>1 AND
            (parent.entry_id IS NULL OR parent.kind<>'DIRECTORY' OR e.relative_path<>(CASE WHEN parent.relative_path='' THEN '' ELSE parent.relative_path||'/' END)||e.filename
             OR e.filename IN ('','.','..') OR contains(e.filename,'/'))""")
        none('next_entry_id does not exceed inventory IDs', "SELECT 1 FROM ${p}scans s JOIN ${p}entries e USING(scan_id) WHERE e.entry_id>=s.next_entry_id")
        none('Directory queue differs from directory inventory', """SELECT 1 FROM ${p}directories d FULL OUTER JOIN
            (SELECT * FROM ${p}entries WHERE kind='DIRECTORY') e USING(scan_id,entry_id)
            WHERE d.entry_id IS NULL OR e.entry_id IS NULL OR d.parent_id<>e.parent_id OR d.relative_path<>e.relative_path""")
        none('Invalid discovery checkpoint', """SELECT 1 FROM ${p}scans s LEFT JOIN ${p}directories d
            ON d.scan_id=s.scan_id AND d.entry_id=s.active_dir WHERE s.active_dir IS NOT NULL AND
            (s.phase<>'DISCOVERING' OR d.entry_id IS NULL OR d.completed)""")
        none('Unfinished directories outside discovery', "SELECT 1 FROM ${p}scans s JOIN ${p}directories d USING(scan_id) WHERE s.phase<>'DISCOVERING' AND NOT d.completed")
        none('Children processed before parent completion', """SELECT 1 FROM ${p}directories child JOIN ${p}directories parent
            ON child.scan_id=parent.scan_id AND child.parent_id=parent.entry_id WHERE child.completed AND NOT parent.completed""")
        none('Inventory under an unfinished non-active parent', """SELECT 1 FROM ${p}entries child JOIN ${p}directories parent
            ON child.scan_id=parent.scan_id AND child.parent_id=parent.entry_id JOIN ${p}scans s ON s.scan_id=child.scan_id
            WHERE NOT parent.completed AND parent.entry_id IS DISTINCT FROM s.active_dir""")
        none('Hash does not reference a regular file', "SELECT 1 FROM ${p}hashes h LEFT JOIN ${p}entries e USING(scan_id,entry_id) WHERE e.entry_id IS NULL OR e.kind<>'FILE'")
        hashColumn('hashes','sha256')
        none('Hashes present during discovery', "SELECT 1 FROM ${p}hashes h JOIN ${p}scans s USING(scan_id) WHERE s.phase='DISCOVERING'")
    }

    private void archive() {
        for (String t : ['archive_runs','archive_inputs','archive_jobs','archive_results']) orphan(t,'scan_id','scans')
        states('archive_runs','phase',"'IDENTIFYING','ANALYZING','PAUSED','COMPLETE','COMPLETE_WITH_ERRORS'")
        states('archive_jobs','status',"'PENDING','COMPLETE','PARTIAL','SKIPPED'")
        states('archive_results','state',"'COMPLETE','PARTIAL','SKIPPED'")
        resultIds('archive_results')
        for (String t : ['archive_inputs','archive_jobs']) orphan(t,'scan_id','archive_runs')
        none('Archive run starts before discovery finishes', "SELECT 1 FROM ${p}archive_runs r JOIN ${p}scans s USING(scan_id) WHERE s.phase='DISCOVERING'")
        unique('archive_inputs','scan_id,source_id'); unique('archive_volumes','result_id,ordinal'); unique('archive_members','result_id,ordinal')
        unique('archive_nested','parent_result_id,group_key')
        for (String t : ['archive_volumes','archive_members','archive_errors']) orphan(t,'result_id','archive_results','result_id')
        for (String col : ['parent_result_id','child_result_id']) orphan('archive_nested',col,'archive_results','result_id')
        none('Archive input disagrees with original inventory', """SELECT 1 FROM ${p}archive_inputs i LEFT JOIN ${p}entries e ON i.scan_id=e.scan_id AND i.source_id=e.entry_id
            WHERE e.entry_id IS NULL OR e.kind<>'FILE' OR i.relative_path IS DISTINCT FROM e.relative_path OR i.size IS DISTINCT FROM e.size
               OR i.modified_sec IS DISTINCT FROM e.modified_sec OR i.modified_nano IS DISTINCT FROM e.modified_nano OR i.group_key IS NULL OR i.flavor IS NULL OR i.slot IS NULL OR i.slot<0""")
        none('Archive jobs do not match grouped inputs', """SELECT 1 FROM ${p}archive_jobs j LEFT JOIN
            (SELECT scan_id,group_key,min(source_id) first_entry,min(flavor) flavor,count(DISTINCT flavor) flavors FROM ${p}archive_inputs GROUP BY scan_id,group_key) i USING(scan_id,group_key)
            WHERE i.first_entry IS NULL OR j.first_entry IS DISTINCT FROM i.first_entry OR j.flavor IS DISTINCT FROM i.flavor OR i.flavors<>1""")
        none('Archive inputs have no job outside identification', """SELECT 1 FROM ${p}archive_inputs i JOIN ${p}archive_runs r USING(scan_id)
            LEFT JOIN ${p}archive_jobs j ON i.scan_id=j.scan_id AND i.group_key=j.group_key WHERE r.phase<>'IDENTIFYING' AND j.scan_id IS NULL""")
        none('Invalid archive work flags',"SELECT 1 FROM ${p}archive_jobs WHERE duplicate IS NULL OR retryable IS NULL OR (status='PARTIAL' AND result_id IS NULL AND diagnostic IS NULL)")
        none('Archive job result is missing or has incompatible state', """SELECT 1 FROM ${p}archive_jobs j LEFT JOIN ${p}archive_results r USING(result_id)
            WHERE (j.result_id IS NOT NULL AND (r.result_id IS NULL OR j.status<>r.state)) OR
            (j.status IN ('COMPLETE','SKIPPED') AND j.result_id IS NULL) OR (j.duplicate AND j.result_id IS NULL)""")
        none('Invalid finalized archive metadata', "SELECT 1 FROM ${p}archive_results WHERE completed_at IS NULL OR height<0 OR policy IS NULL OR provider IS NULL OR (state='SKIPPED' AND (reusable OR retryable))")
        for (String t : ['archive_volumes','archive_members']) none('Invalid ordinal in ' + t,"SELECT 1 FROM ${p}${t} WHERE ordinal IS NULL OR ordinal<1")
        states('archive_members','kind',"'FILE','DIRECTORY','SYMLINK','HARDLINK','OTHER'")
        states('archive_members','integrity',"'READ_OK','DAMAGED','UNREADABLE','METADATA','SKIPPED','ENCRYPTED'")
        timestamps('archive_members')
        none('Invalid archive volume metadata',"SELECT 1 FROM ${p}archive_volumes WHERE source_id IS NULL OR source_id<1 OR size IS NULL OR size<0 OR slot IS NULL OR slot<0")
        hashColumn('archive_volumes','sha256'); hashColumn('archive_members','sha256'); hashColumn('archive_members','recovered_sha256')
        none('Invalid confirmed archive member hash', """SELECT 1 FROM ${p}archive_members WHERE
            (sha256 IS NOT NULL AND (kind IS DISTINCT FROM 'FILE' OR integrity IS DISTINCT FROM 'READ_OK' OR actual_size IS NULL OR actual_size<0 OR recovered_sha256 IS NOT NULL)) OR
            (integrity='READ_OK' AND (sha256 IS NULL OR (declared_size IS NOT NULL AND declared_size<>actual_size)))""")
        none('Skipped archive unexpectedly has contents',"SELECT 1 FROM ${p}archive_results r JOIN ${p}archive_members m USING(result_id) WHERE r.state='SKIPPED'")
        none('Nested archive source is not an eligible parent member', """SELECT 1 FROM ${p}archive_nested n LEFT JOIN ${p}archive_members m
            ON n.parent_result_id=m.result_id AND n.source_ordinal=m.ordinal
            WHERE m.ordinal IS NULL OR m.kind<>'FILE' OR m.sha256 IS NULL OR m.group_key IS DISTINCT FROM n.group_key""")
        // Strictly decreasing stored height proves acyclicity without exploding a DAG into all paths.
        none('Archive DAG cycle or inconsistent height', """SELECT 1 FROM ${p}archive_nested n JOIN ${p}archive_results a ON n.parent_result_id=a.result_id
            JOIN ${p}archive_results b ON n.child_result_id=b.result_id WHERE a.height<=b.height""")
        // Native errors can precede a failed header: non-null error ordinals need not have a member row.
    }

    private void image() {
        for (String t : ['image_runs','image_jobs','image_results']) orphan(t,'scan_id','scans')
        resultIds('image_results')
        orphan('image_jobs','scan_id','image_runs')
        states('image_runs','phase',"'IDENTIFYING','ANALYZING','PAUSED','COMPLETE','COMPLETE_WITH_ERRORS'")
        states('image_jobs','state',"'PENDING','COMPLETE','PARTIAL','COMPONENT'")
        states('image_results','state',"'COMPLETE','PARTIAL'")
        none('Image run starts before discovery finishes',"SELECT 1 FROM ${p}image_runs r JOIN ${p}scans s USING(scan_id) WHERE s.phase='DISCOVERING'")
        for (String t : IMAGE - ['image_runs','image_jobs','image_results']) orphan(t,'result_id','image_results','result_id')
        none('Image job disagrees with original inventory', """SELECT 1 FROM ${p}image_jobs j LEFT JOIN ${p}entries e ON j.scan_id=e.scan_id AND j.source_id=e.entry_id
            WHERE e.entry_id IS NULL OR e.kind<>'FILE' OR j.relative_path IS DISTINCT FROM e.relative_path OR j.size IS DISTINCT FROM e.size
               OR j.modified_sec IS DISTINCT FROM e.modified_sec OR j.modified_nano IS DISTINCT FROM e.modified_nano""")
        none('Image result provenance has no regular source',"SELECT 1 FROM ${p}image_results r LEFT JOIN ${p}entries e ON r.scan_id=e.scan_id AND r.source_id=e.entry_id WHERE e.entry_id IS NULL OR e.kind<>'FILE'")
        none('Invalid image component job',"""SELECT 1 FROM ${p}image_jobs j LEFT JOIN ${p}image_jobs primary_job
            ON j.scan_id=primary_job.scan_id AND j.component_of=primary_job.source_id WHERE
            (j.state='COMPONENT' AND (j.component_of IS NULL OR primary_job.source_id IS NULL OR j.source_id=j.component_of OR primary_job.state='COMPONENT'))
            OR (j.state<>'COMPONENT' AND j.component_of IS NOT NULL)""")
        none('Image job result is missing or has incompatible state', """SELECT 1 FROM ${p}image_jobs j LEFT JOIN ${p}image_results r USING(result_id)
            WHERE (j.result_id IS NOT NULL AND (r.result_id IS NULL OR (j.state NOT IN ('PENDING','COMPONENT') AND j.state<>r.state)))
            OR (j.state='COMPLETE' AND j.result_id IS NULL) OR (j.duplicate AND j.result_id IS NULL)""")
        none('Invalid finalized image result',"SELECT 1 FROM ${p}image_results WHERE completed_at IS NULL OR policy IS NULL OR provider IS NULL OR (reusable AND (state<>'COMPLETE' OR retryable))")
        unique('image_components','result_id,ordinal'); unique('image_partitions','result_id,device,number')
        unique('image_filesystems','result_id,filesystem_id'); unique('image_filesystems','result_id,device')
        none('Invalid guest filesystem identity',"SELECT 1 FROM ${p}image_filesystems WHERE filesystem_id IS NULL OR filesystem_id<1 OR device IS NULL")
        states('image_filesystems','state',"'COMPLETE','PARTIAL','UNMOUNTABLE','ENCRYPTED','NOT_A_FILESYSTEM'")
        unique('image_entries','result_id,filesystem_id,entry_id'); unique('image_entries','result_id,filesystem_id,relative_path')
        none('Dangling guest filesystem', "SELECT 1 FROM ${p}image_entries e LEFT JOIN ${p}image_filesystems f USING(result_id,filesystem_id) WHERE f.filesystem_id IS NULL")
        none('Invalid guest-file identity',"SELECT 1 FROM ${p}image_entries WHERE entry_id IS NULL OR entry_id<1 OR filesystem_id IS NULL OR filesystem_id<1 OR relative_path IS NULL OR filename IS NULL")
        states('image_entries','kind',"'FILE','DIRECTORY','SYMLINK','OTHER'")
        states('image_entries','integrity',"'READ_OK','UNREADABLE','METADATA_ONLY'")
        timestamps('image_entries'); timestamps('image_components')
        hashColumn('image_entries','sha256'); hashColumn('image_components','sha256')
        none('Invalid confirmed guest hash', """SELECT 1 FROM ${p}image_entries WHERE
            (sha256 IS NOT NULL AND (kind IS DISTINCT FROM 'FILE' OR integrity IS DISTINCT FROM 'READ_OK' OR actual_size IS NULL OR actual_size<0 OR actual_size IS DISTINCT FROM size))
            OR (integrity='READ_OK' AND sha256 IS NULL)""")
        none('Image component has invalid provenance', """SELECT 1 FROM ${p}image_components c JOIN ${p}image_results r USING(result_id)
            LEFT JOIN ${p}entries e ON e.scan_id=r.scan_id AND e.entry_id=c.source_id WHERE e.entry_id IS NULL OR e.kind<>'FILE'
            OR c.relative_path IS DISTINCT FROM e.relative_path OR c.size IS DISTINCT FROM e.size OR c.ordinal IS NULL OR c.ordinal<0""")
        none('Image component graph is cyclic or dangling', """SELECT 1 FROM ${p}image_components c LEFT JOIN ${p}image_components parent
            ON c.result_id=parent.result_id AND c.parent_ordinal=parent.ordinal WHERE
            c.parent_ordinal IS NULL OR c.parent_ordinal < -1 OR (c.parent_ordinal=-1 AND (c.ordinal<>0 OR c.role<>'PRIMARY')) OR (c.parent_ordinal>=0 AND (parent.ordinal IS NULL OR parent.ordinal>=c.ordinal))""")
        // A source-change/native-failure result can retain diagnostics after discarding rows.
        String exceptional="EXISTS (SELECT 1 FROM ${p}image_errors fatal WHERE fatal.result_id=e.result_id AND fatal.code IN ('SOURCE_CHANGED','NATIVE_PROCESS_ERROR'))"
        none('Dangling image error filesystem',"""SELECT 1 FROM ${p}image_errors e LEFT JOIN ${p}image_filesystems f USING(result_id,filesystem_id)
            WHERE e.filesystem_id IS NOT NULL AND f.filesystem_id IS NULL AND NOT (${exceptional})""")
        none('Dangling image error entry',"""SELECT 1 FROM ${p}image_errors e LEFT JOIN ${p}image_entries entry
            ON e.result_id=entry.result_id AND e.filesystem_id=entry.filesystem_id AND e.entry_id=entry.entry_id
            WHERE e.entry_id IS NOT NULL AND entry.entry_id IS NULL AND NOT (${exceptional})""")
    }
}
