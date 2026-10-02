package fnord.dedup.merge

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.DatabaseLock
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Whole-database transfer, not recovery/repair. Source is never opened for writing. */
class DatabaseMerger {
    static final String VERSION = 'database-merge-v1'
    final ScanOptions tuning
    final StopToken stop
    Closure progress = { Map ignored -> }

    DatabaseMerger(ScanOptions tuning = new ScanOptions(), StopToken stop = new StopToken()) {
        this.tuning = tuning.validate(); this.stop = stop
    }

    /** Synchronous. Report receives (summary, streamScanMappings); do not retain the stream closure.
     * A fresh plan is audited under both locks on every invocation; detached dry-runs are not executable plans.
     */
    Map merge(Path requestedSource, Path requestedDestination, boolean dryRun = false, Closure report = null) {
        Path source = existing(requestedSource), destination = existing(requestedDestination)
        if (Files.isSameFile(source,destination)) throw new DatabaseMergeException('SAME_DATABASE','Source and destination are the same physical database')
        List<Path> paths = [source,destination].sort { it.toString() }
        validateControlPaths(paths)
        List<DatabaseLock> locks = []
        Path scratch = null
        def c = null
        def watcher = null
        boolean inTransaction = false, committed = false
        Throwable problem = null
        try {
            for (Path path : paths) {
                stop.check()
                try { locks.add(new DatabaseLock(Path.of(path.toString()+'.lock'))) }
                catch (IllegalStateException e) { throw new DatabaseMergeException('DATABASE_LOCKED',path.toString()+': '+e.message) }
            }
            // No Dedup/DuckStore construction here: those initialize schemas and may recover work.
            c = DriverManager.getConnection('jdbc:duckdb:')
            MergeSql q = new MergeSql(c,stop)
            watcher = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
                Thread t = new Thread(r,'fnord-merge-cancellation'); t.daemon=true; t
            } as java.util.concurrent.ThreadFactory)
            watcher.scheduleWithFixedDelay({ q.cancelActive() } as Runnable, 50, 50, TimeUnit.MILLISECONDS)
            scratch = Files.createTempDirectory('fnord-merge-')
            q.exec('SET temp_directory = '+MergeSql.literal(scratch.toString()))
            q.exec('SET threads = '+tuning.databaseThreads)
            q.exec('SET memory_limit = '+MergeSql.literal(tuning.memoryLimit))
            q.exec('SET autoinstall_known_extensions=false')
            q.exec('SET autoload_known_extensions=false')
            attach(q,source,'src',true)
            attach(q,destination,'dst',true)
            Map reference = DatabaseAuditor.expectedSchema()
            event('AUDITING', [database:'source'])
            Set<String> sf = new DatabaseAuditor(q,'src',reference).audit()
            event('AUDITING', [database:'destination'])
            Set<String> df = new DatabaseAuditor(q,'dst',reference).audit()
            nameConflicts(q)
            List<String> tables = new ArrayList<>(DatabaseAuditor.BASE)
            if ('archive' in sf) tables.addAll(DatabaseAuditor.ARCHIVE)
            if ('image' in sf) tables.addAll(DatabaseAuditor.IMAGE)
            Map<String,Long> counts = [:]
            tables.each { counts[it] = (q.scalar("SELECT count(*) FROM src.main.${it}") as Number).longValue() }
            q.exec('CREATE TEMP TABLE import_scan_map(old_scan_id BIGINT PRIMARY KEY,new_scan_id BIGINT UNIQUE,name VARCHAR)')
            scanMap(q)
            for (String feature : sf - ['base']) resultMap(q,feature,df.contains(feature))
            Map summary = [version:VERSION,status:dryRun?'READY':'IMPORTED',dry_run:dryRun,
                source:source.toString(),destination:destination.toString(),rows:counts,
                features_to_initialize:(sf-df).sort(),scans_imported:dryRun?0:counts.scans,
                scans_to_import:counts.scans]
            def range = q.rows('SELECT min(new_scan_id) first_id,max(new_scan_id) last_id FROM import_scan_map')[0]
            summary.new_scan_id_first=range.first_id; summary.new_scan_id_last=range.last_id
            if (counts.scans==0) summary.status='NO_SCANS'
            if (!dryRun && counts.scans>0) {
                // Reopen only the destination writable, retaining both application locks and the
                // native read lock on source. Re-audit under the new destination native write lock.
                q.exec('DETACH dst')
                attach(q,destination,'dst',false)
                Set<String> reopened = new DatabaseAuditor(q,'dst',reference).audit()
                if (reopened != df) throw new DatabaseMergeException('DESTINATION_CHANGED','Destination schema changed during preflight')
                nameConflicts(q)
                if ((q.scalar('SELECT count(*) FROM dst.main.scans s JOIN import_scan_map m ON s.scan_id=m.new_scan_id') as long)>0)
                    throw new DatabaseMergeException('DESTINATION_CHANGED','Destination IDs changed during preflight')
                q.exec('USE dst')
                c.autoCommit=false; inTransaction=true
                q.exec('SELECT 1')
                for (String feature : sf-df) {
                    if (feature=='base') throw new IllegalStateException('Destination base schema must already exist')
                    DatabaseAuditor.schema(feature).split(';').findAll { it.trim() }.each { q.exec(it) }
                    String id=UUID.randomUUID().toString()
                    while (id == q.scalar("SELECT instance_id FROM src.main.${feature}_schema_info")) id=UUID.randomUUID().toString()
                    q.exec("INSERT INTO dst.main.${feature}_schema_info VALUES (1,?)",[id])
                }
                for (String table : tables) {
                    stop.check()
                    List<String> cols = reference.columns.findAll { it.table_name==table }*.column_name
                    String columns = cols.collect { MergeSql.identifier(it) }.join(',')
                    String transformed = projection(table,cols)
                    event('COPYING',[table:table,rows:counts[table]])
                    q.exec("INSERT INTO dst.main.${table} (${columns}) ${transformed}")
                    event('TABLE_COPIED',[table:table,rows:counts[table]])
                    String selected = imported(table,cols)
                    long actual = (q.scalar("SELECT count(*) FROM (${selected}) imported_rows") as Number).longValue()
                    if (actual != counts[table]) throw new DatabaseMergeException('ROW_COUNT_MISMATCH','Imported row count differs: '+table,4)
                    // Exact multiset verification catches errors that equal counts alone cannot detect.
                    for (List<String> pair : [[transformed,selected],[selected,transformed]]) {
                        if (!q.rows("SELECT 1 FROM ((${pair[0]}) EXCEPT ALL (${pair[1]})) differences LIMIT 1").empty)
                            throw new DatabaseMergeException('ROW_CONTENT_MISMATCH','Imported values differ: '+table,4)
                    }
                }
                event('POST_AUDIT')
                new DatabaseAuditor(q,'dst',reference).audit()
                event('BEFORE_COMMIT')
                q.commit(); committed=true; inTransaction=false
                c.autoCommit=true
                summary.completed_at=java.time.Instant.now().toString()
            }
            if (!summary.containsKey('completed_at')) summary.completed_at=java.time.Instant.now().toString()
            if (report != null) {
                // All publication happens AFTER COMMIT, or after successful read-only dry-run.
                report(summary, { Closure consumer ->
                    // Cancellation after commit must not turn success into a claimed rollback.
                    new MergeSql(c).each('SELECT old_scan_id,new_scan_id,name FROM import_scan_map ORDER BY old_scan_id',[],consumer)
                })
            }
            summary
        } catch (Throwable failure) {
            problem=failure
            if (inTransaction && c!=null) {
                try { c.rollback() } catch (Throwable rollback) { failure.addSuppressed(rollback) }
                inTransaction=false
            }
            if (committed) {
                DatabaseMergeException e = new DatabaseMergeException('COMMITTED_REPORT_FAILURE',
                    'Database import COMMITTED, but reporting/cleanup failed: '+failure.message,1,[database_committed:true])
                e.addSuppressed(failure); throw e
            }
            throw failure
        } finally {
            watcher?.shutdownNow()
            List<Throwable> cleanup=[]
            // Closing detaches the read-only source even if DETACH could not be issued.
            try { c?.close() } catch (Throwable e) { cleanup.add(e) }
            try { if (scratch!=null) removeScratch(scratch) } catch (Throwable e) { cleanup.add(e) }
            locks.reverseEach { DatabaseLock lock ->
                try { lock.close() } catch (Throwable e) { cleanup.add(e) }
            }
            if (!cleanup.empty) {
                if (problem!=null) cleanup.each { problem.addSuppressed(it) }
                else {
                    def error=new DatabaseMergeException(committed?'COMMITTED_CLEANUP_FAILURE':'CLEANUP_FAILURE',
                        (committed?'Import COMMITTED. ':'')+'Cleanup failed: '+cleanup[0].message,1,[database_committed:committed])
                    cleanup.each { error.addSuppressed(it) }
                    throw error
                }
            }
        }
    }

    private void event(String phase,Map detail=[:]) { stop.check(); progress([stage:'database_merge',phase:phase]+detail); stop.check() }

    private static Path existing(Path path) {
        if (path==null) throw new DatabaseMergeException('MISSING_PATH','Both source and destination are required')
        Path real
        try { real=path.toRealPath() } catch (IOException e) { throw new DatabaseMergeException('MISSING_DATABASE','Database must already exist: '+path) }
        if (!Files.isRegularFile(real,LinkOption.NOFOLLOW_LINKS) || Files.size(real)==0)
            throw new DatabaseMergeException('INVALID_DATABASE','Expected a nonempty regular database file: '+path)
        real
    }

    private static void validateControlPaths(List<Path> paths) {
        for (Path a : paths) for (Path b : paths) if (a!=b) {
            if (b.toString() in [a.toString()+'.wal',a.toString()+'.lock'] || b.startsWith(Path.of(a.toString()+'.tmp')) ||
                b.startsWith(Path.of(a.toString()+'.archives-tmp')) || b.startsWith(Path.of(a.toString()+'.images-tmp')))
                throw new DatabaseMergeException('CONTROL_PATH_CONFLICT','Database path overlaps another database control/temp path')
        }
        List<Path> locks=paths.collect { Path.of(it.toString()+'.lock') }
        for (Path lock : locks) if (Files.exists(lock,LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS))
                throw new DatabaseMergeException('UNSAFE_LOCK','Database lock must be a regular non-symlink file: '+lock)
            for (Path path : paths) if (Files.isSameFile(lock,path))
                throw new DatabaseMergeException('UNSAFE_LOCK','Lock aliases a database file')
        }
        if (locks.every { Files.exists(it) } && Files.isSameFile(locks[0],locks[1]))
            throw new DatabaseMergeException('UNSAFE_LOCK','Source and destination lock files alias one another')
    }

    private static void attach(MergeSql q,Path path,String alias,boolean readOnly) {
        try { q.exec('ATTACH '+MergeSql.literal(path.toString())+' AS '+alias+' (TYPE DUCKDB, '+(readOnly?'READ_ONLY':'READ_WRITE')+')') }
        catch (java.sql.SQLException e) {
            throw new DatabaseMergeException('DATABASE_OPEN_FAILED',
                'Cannot open '+alias+' '+path+' '+(readOnly?'read-only':'read-write')+'. Keep any WAL with its database; close/recover it with the originating application first if required. '+e.message,1)
        }
    }

    private static void nameConflicts(MergeSql q) {
        String query='SELECT s.name FROM src.main.scans s JOIN dst.main.scans d ON s.name=d.name'
        long count=(q.scalar('SELECT count(*) FROM ('+query+') conflicts') as Number).longValue()
        if (count>0) {
            List names=q.rows(query+' ORDER BY s.name LIMIT 1000')*.name
            throw new DatabaseMergeException('SCAN_NAME_CONFLICT','Merge refused: '+count+' overlapping scan name(s)',3,
                [conflicting_scan_names:names,conflict_count:count,truncated:count>names.size()])
        }
    }

    private void scanMap(MergeSql q) {
        long next=(q.scalar('SELECT coalesce(max(scan_id),0) FROM dst.main.scans') as Number).longValue()
        long after=0
        while (true) {
            def page=q.rows('SELECT scan_id,name FROM src.main.scans WHERE scan_id>? ORDER BY scan_id LIMIT 1024',[after])
            if (page.empty) break
            q.statement('INSERT INTO import_scan_map VALUES (?,?,?)',[]) { s ->
                for (Map row : page) {
                    stop.check()
                    try {
                        next=Math.addExact(next,1L)
                        if (next==(row.scan_id as long)) next=Math.addExact(next,1L)
                    } catch (ArithmeticException e) { throw new DatabaseMergeException('SCAN_ID_EXHAUSTED','BIGINT scan ID space exhausted') }
                    s.setLong(1,row.scan_id as long); s.setLong(2,next); s.setString(3,row.name as String); s.addBatch()
                    after=row.scan_id as long
                }
                s.executeBatch()
            }
        }
    }

    private void resultMap(MergeSql q,String feature,boolean existsInDestination) {
        String map='import_'+feature+'_result_map', table=feature+'_results'
        q.exec("CREATE TEMP TABLE ${map}(old_result_id VARCHAR PRIMARY KEY,new_result_id VARCHAR UNIQUE)")
        String after=''
        while (true) {
            def page=q.rows("SELECT result_id FROM src.main.${table} WHERE result_id>? ORDER BY result_id LIMIT 1024",[after])
            if (page.empty) break
            q.statement("INSERT INTO ${map} VALUES (?,?)",[]) { s ->
                for (Map row : page) {
                    stop.check()
                    s.setString(1,row.result_id as String); s.setString(2,UUID.randomUUID().toString()); s.addBatch()
                    after=row.result_id as String
                }
                s.executeBatch()
            }
        }
        // Check actual generated identities, including against all source IDs (not just each old ID).
        String overlap="SELECT m.old_result_id FROM ${map} m WHERE EXISTS (SELECT 1 FROM src.main.${table} s WHERE s.result_id=m.new_result_id)"
        if (existsInDestination) overlap+=" OR EXISTS (SELECT 1 FROM dst.main.${table} d WHERE d.result_id=m.new_result_id)"
        while (true) {
            def collisions=q.rows(overlap+' LIMIT 1024')
            if (collisions.empty) break
            collisions.each { q.exec("UPDATE ${map} SET new_result_id=? WHERE old_result_id=?",[UUID.randomUUID().toString(),it.old_result_id]) }
        }
        if ((q.scalar("SELECT count(*) FROM ${map}") as long)!=(q.scalar("SELECT count(*) FROM src.main.${table}") as long))
            throw new DatabaseMergeException('RESULT_MAP_INCOMPLETE','Some result identifiers are invalid or unmapped',4)
    }

    static String projection(String table,List<String> cols) {
        List<String> joins=[]
        if ('scan_id' in cols) joins.add('JOIN import_scan_map sm ON sm.old_scan_id=t.scan_id')
        String feature=table.startsWith('archive_')?'archive':table.startsWith('image_')?'image':null
        Map<String,String> translations=[:]
        if ('scan_id' in cols) translations.scan_id='sm.new_scan_id'
        for (String key : ['result_id','parent_result_id','child_result_id']) if (key in cols) {
            String alias='m_'+key
            joins.add("LEFT JOIN import_${feature}_result_map ${alias} ON ${alias}.old_result_id=t.${key}")
            translations[key]=alias+'.new_result_id'
        }
        'SELECT '+cols.collect { (translations[it] ?: 't.'+MergeSql.identifier(it))+' AS '+MergeSql.identifier(it) }.join(',')+
            " FROM src.main.${table} t "+joins.join(' ')
    }

    private static String imported(String table,List<String> cols) {
        String scope='scan_id' in cols?'scan_id':('parent_result_id' in cols?'parent_result_id':'result_id')
        String map=scope=='scan_id'?'import_scan_map':('import_'+(table.startsWith('archive_')?'archive':'image')+'_result_map')
        String newKey=scope=='scan_id'?'new_scan_id':'new_result_id'
        'SELECT '+cols.collect { 't.'+MergeSql.identifier(it) }.join(',')+" FROM dst.main.${table} t JOIN ${map} m ON t.${scope}=m.${newKey}"
    }

    private static void removeScratch(Path directory) {
        // Only the exact freshly created directory is removed, without following links.
        Files.walkFileTree(directory,new SimpleFileVisitor<Path>() {
            @Override FileVisitResult visitFile(Path file,BasicFileAttributes attrs) { Files.delete(file); FileVisitResult.CONTINUE }
            @Override FileVisitResult postVisitDirectory(Path dir,IOException error) {
                if (error!=null) throw error
                Files.delete(dir); FileVisitResult.CONTINUE
            }
        })
    }
}
