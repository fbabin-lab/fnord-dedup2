package fnord.dedup

import fnord.dedup.archive.*
import fnord.dedup.image.*
import fnord.dedup.merge.*
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import groovy.json.JsonSlurper
import java.nio.file.*
import java.sql.DriverManager
import java.security.MessageDigest
import static org.junit.jupiter.api.Assertions.*

class DatabaseMergeTest {
    @TempDir Path work
    static ScanOptions tuning() { new ScanOptions(batchSize:2,databaseThreads:1,memoryLimit:'128MB') }
    Path db(String name) { work.resolve(name+'.duckdb') }
    DatabaseMerger merger(StopToken stop=new StopToken()) { new DatabaseMerger(tuning(),stop) }
    static String digest(Path path) { HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path))) }
    void open(Path path,Closure body) { Dedup.open(path,tuning()).withCloseable(body) }
    static List<Map> rows(Path path,String query) {
        DriverManager.getConnection('jdbc:duckdb:'+path).withCloseable { c -> new MergeSql(c).rows(query) }
    }
    void mutate(Path path,String query) {
        DriverManager.getConnection('jdbc:duckdb:'+path).withCloseable { c -> new MergeSql(c).exec(query) }
    }
    Path basic(String filename,String name,boolean complete=true) {
        Path root=Files.createDirectories(work.resolve(filename+'-root'))
        Files.writeString(root.resolve('one'),'same')
        Files.writeString(root.resolve('two'),'same')
        open(db(filename)) { it.scan(name,root,new StopToken(),!complete) }
        db(filename)
    }
    Map run(Path source,Path dest,boolean dry=false,Closure mapping={}) {
        merger().merge(source,dest,dry) { summary,stream -> stream(mapping) }
    }
    DatabaseMergeException refusal(Path src,Path dst,String code=null) {
        String before=digest(dst),srcBefore=digest(src)
        def e=assertThrows(DatabaseMergeException) { run(src,dst) }
        if(code) assert e.code==code : e.message
        assert digest(src)==srcBefore
        assert digest(dst)==before
        e
    }

    @Test void baseRowsRemappedNamesRootsAndTimestampsPreservedSourceUnchanged() {
        Path src=basic('source','Photos'),dst=basic('dest','Backup')
        String sourceHash=digest(src)
        def original=rows(src,'SELECT * FROM scans')[0]
        List mappings=[]
        Map summary=run(src,dst,false) { mappings.add(it) }
        assert summary.status=='IMPORTED' && summary.rows.entries==3 && summary.rows.hashes==2
        assert mappings==[[old_scan_id:1L,new_scan_id:2L,name:'Photos']]
        assert digest(src)==sourceHash
        def imported=rows(dst,"SELECT * FROM scans WHERE name='Photos'")[0]
        assert imported.scan_id==2
        assert imported.findAll { it.key!='scan_id' }==original.findAll { it.key!='scan_id' }
        assert rows(dst,'SELECT count(*) n FROM hashes')[0].n==4
        // Subsequent ordinary allocation must see imported IDs.
        open(dst) { d -> assert d.createScan('next',work.resolve('dest-root')).scan_id==3 }
    }

    @Test void identityRemapsEvenWithEmptyDestinationAndNoNumericalConflict() {
        Path src=basic('source','A'); open(db('dest')) {}
        List map=[]; run(src,db('dest'),false) { map.add(it) }
        assert map[0].new_scan_id!=map[0].old_scan_id
        assert rows(db('dest'),'SELECT scan_id FROM scans')[0].scan_id==2
        Path other=basic('other','B')
        open(other) { d ->
            for (String table:DatabaseAuditor.BASE) d.store.exec("UPDATE ${table} SET scan_id=500")
        }
        map.clear();run(other,db('dest'),false){map.add(it)}
        assert map[0].new_scan_id==3 && map[0].old_scan_id==500
    }

    @Test void allNameConflictsRefuseEntireTransferAndCaseRemainsExact() {
        Path src=basic('source','Photos'),dst=basic('dest','Photos')
        open(src) { it.scan('Second',work.resolve('source-root')) }
        open(dst) { it.scan('Second',work.resolve('dest-root')) }
        def e=refusal(src,dst,'SCAN_NAME_CONFLICT')
        assert e.details.conflicting_scan_names==['Photos','Second']
        mutate(src,"UPDATE scans SET name=lower(name)")
        assert run(src,dst).scans_imported==2
    }

    @Test void dryRunAndEmptySourceAreByteUnchangedAndDoNotInitializeFeatures() {
        Path src=fixture('source','source',true,true),dst=basic('dest','dest')
        String a=digest(src),b=digest(dst)
        Map plan=run(src,dst,true)
        assert plan.status=='READY' && plan.features_to_initialize==['archive','image']
        assert digest(src)==a && digest(dst)==b
        assert rows(dst,"SELECT count(*) n FROM information_schema.tables WHERE table_name='image_results'")[0].n==0
        open(db('empty')) {}
        assert run(db('empty'),dst).status=='NO_SCANS'
        assert digest(dst)==b
    }

    @Test void schemaRefusalsIncludeUnknownObjectsPartialFeaturesColumnsAndVersions() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        mutate(src,'CREATE TABLE future_feature (scan_id BIGINT)')
        refusal(src,dst,'SCHEMA_MISMATCH');mutate(src,'DROP TABLE future_feature')
        mutate(src,'CREATE VIEW external_view AS SELECT 1')
        refusal(src,dst,'SCHEMA_MISMATCH');mutate(src,'DROP VIEW external_view')
        mutate(src,'CREATE TABLE archive_jobs (id BIGINT)')
        refusal(src,dst,'SCHEMA_MISMATCH');mutate(src,'DROP TABLE archive_jobs')
        mutate(src,'ALTER TABLE entries ADD COLUMN extra VARCHAR')
        refusal(src,dst,'SCHEMA_MISMATCH');mutate(src,'ALTER TABLE entries DROP COLUMN extra')
        mutate(src,'UPDATE schema_info SET version=2')
        refusal(src,dst,'SCHEMA_VERSION')
    }

    @Test void missingAndSameDatabaseAliasesAreRejectedIncludingHardlinks() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        assertThrows(DatabaseMergeException) { run(src,work.resolve('missing')) }
        assert !Files.exists(work.resolve('missing'))
        refusal(src,src,'SAME_DATABASE')
        Path link=Files.createSymbolicLink(work.resolve('alias'),src)
        refusal(src,link,'SAME_DATABASE')
        Path hard=Files.createLink(work.resolve('hard'),src)
        refusal(src,hard,'SAME_DATABASE')
    }

    @Test void holdsBothNormalApplicationLocksAndReleasesThemOnFailure() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        for(Path busy:[src,dst]) {
            new DatabaseLock(Path.of(busy.toString()+'.lock')).withCloseable {
                refusal(src,dst,'DATABASE_LOCKED')
            }
        }
        assert run(src,dst).status=='IMPORTED'
    }

    @Test void badReferencesOnEitherSideRefuseWithoutModification() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        mutate(src,"INSERT INTO hashes VALUES (1,999,repeat('a',64))")
        refusal(src,dst,'INCONSISTENT_DATABASE')
        mutate(src,'DELETE FROM hashes WHERE entry_id=999')
        mutate(dst,"UPDATE entries SET parent_id=999 WHERE entry_id=2")
        refusal(src,dst,'INCONSISTENT_DATABASE')
    }

    @Test void duplicatePathIdsOrHashesAreRefused() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        for(String table:['entries','hashes']) {
            mutate(src,"INSERT INTO ${table} SELECT * FROM ${table} LIMIT 1")
            refusal(src,dst,'INCONSISTENT_DATABASE')
            // Restore independent fixture for the next case.
            mutate(src,"CREATE TEMP TABLE saved AS SELECT DISTINCT * FROM ${table}; DELETE FROM ${table}; INSERT INTO ${table} SELECT * FROM saved")
        }
    }

    @Test void outOfRangeAllocatorRefusesBeforeWrites() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        open(dst) { d -> for(String t:DatabaseAuditor.BASE) d.store.exec("UPDATE ${t} SET scan_id=9223372036854775807") }
        refusal(src,dst,'SCAN_ID_EXHAUSTED')
    }

    @Test void interruptedDiscoveryCanBeMergedThenNormallyResumed() {
        Path src=db('source'),dst=basic('dest','dest')
        Path root=Files.createDirectory(work.resolve('discover'))
        10.times { Files.writeString(root.resolve('f'+it),'abc') }
        open(src) { d ->
            d.createScan('discover',root)
            StopToken token=new StopToken()
            d.progress={ Map event -> token.cancel() }
            d.discover('discover',token)
            assert d.store.scan('discover').phase=='DISCOVERING'
        }
        assert run(src,dst).status=='IMPORTED'
        open(dst) { d ->
            d.resume('discover')
            assert d.status('discover').phase=='COMPLETE'
            assert d.status('discover').files==10
        }
    }

    @Test void rollbackIncludesFeatureInitializationAndEveryCopiedTable() {
        Path src=fixture('source','source',true,true),dst=basic('dest','dest')
        def before=rows(dst,'SELECT * FROM scans')
        for(String failTable:['scans','entries','archive_members','image_entries']) {
            def service=merger()
            service.progress={ Map e -> if(e.phase=='TABLE_COPIED' && e.table==failTable) throw new IOException('injected failure') }
            assertThrows(IOException) { service.merge(src,dst) }
            assert rows(dst,'SELECT * FROM scans')==before
            assert rows(dst,"SELECT count(*) n FROM information_schema.tables WHERE table_name='archive_schema_info'")[0].n==0
        }
    }

    @Test void cancellationBeforeCommitRollsBackAndPostCommitReportFailureIsExplicit() {
        Path src=basic('source','source'),dst=basic('dest','dest')
        StopToken stop=new StopToken()
        def service=merger(stop)
        service.progress={e -> if(e.phase=='BEFORE_COMMIT') stop.cancel() }
        assertThrows(java.util.concurrent.CancellationException) { service.merge(src,dst) }
        assert rows(dst,'SELECT count(*) n FROM scans')[0].n==1
        def error=assertThrows(DatabaseMergeException) {
            merger().merge(src,dst,false) { summary,stream -> throw new IOException('broken report') }
        }
        assert error.code=='COMMITTED_REPORT_FAILURE' && error.details.database_committed
        assert rows(dst,'SELECT count(*) n FROM scans')[0].n==2
    }

    @Test void optionalFeatureMatrixAndInstanceOwnershipArePreserved() {
        for(int s=0;s<4;s++) for(int d=0;d<4;d++) {
            Path src=fixture('s'+s+'d'+d,'source',(s&1)!=0,(s&2)!=0)
            Path dst=fixture('d'+s+'d'+d,'dest',(d&1)!=0,(d&2)!=0)
            Map ids=[:]
            for(String feature:['archive','image']) if((d&(feature=='archive'?1:2))!=0)
                ids[feature]=rows(dst,"SELECT instance_id FROM ${feature}_schema_info")[0].instance_id
            Map result=run(src,dst)
            assert result.status=='IMPORTED'
            for(String feature:['archive','image']) if(((s|d)&(feature=='archive'?1:2))!=0) {
                String id=rows(dst,"SELECT instance_id FROM ${feature}_schema_info")[0].instance_id
                if(ids.containsKey(feature)) assert id==ids[feature]
                else assert id!=rows(src,"SELECT instance_id FROM ${feature}_schema_info")[0].instance_id
                // Originating registries never travel across the import boundary.
                assert rows(dst,"SELECT * FROM ${feature}_temp_roots WHERE path='/foreign/source'").empty
            }
        }
    }

    @Test void sharedArchiveDAGAndImageResultsAreRemappedOnceIncludingUuidCollisions() {
        Path src=fixture('source','source',true,true),dst=fixture('dest','dest',true,true)
        open(src) { d ->
            d.scan('second',work.resolve('source-root'),new StopToken(),true)
            analyze(d,'second',true,true)
            assert d.archiveStatus('second').duplicate_roots==1
            assert d.imageStatus('second').duplicates==1
        }
        // Force a real collision between copied databases while preserving each one's graph.
        for(String feature:['archive','image']) {
            String srcId=rows(src,"SELECT result_id FROM ${feature}_results ORDER BY result_id LIMIT 1")[0].result_id
            String dstId=rows(dst,"SELECT result_id FROM ${feature}_results ORDER BY result_id LIMIT 1")[0].result_id
            open(dst) { d ->
                def tables=feature=='archive'?DatabaseAuditor.ARCHIVE:DatabaseAuditor.IMAGE
                for(String t:tables) {
                    def cols=d.store.rows("SELECT column_name FROM information_schema.columns WHERE table_name=?",t)*.column_name
                    for(String col:['result_id','parent_result_id','child_result_id']) if(col in cols) d.store.exec("UPDATE ${t} SET ${col}=? WHERE ${col}=?",srcId,dstId)
                }
            }
        }
        mutate(dst,"UPDATE archive_members SET filename='dest-leaf',relative_path='dest-leaf',sha256=repeat('d',64) WHERE group_key IS NULL")
        mutate(dst,"UPDATE image_entries SET filename='dest-leaf',relative_path='/dest-leaf',sha256=repeat('d',64)")
        def sourceIds=rows(src,'SELECT result_id FROM archive_results')*.result_id
        assert run(src,dst).scans_imported==2
        open(dst) { d ->
            def a=[];d.eachArchive('source'){a.add(it)}
            def b=[];d.eachArchive('second'){b.add(it)}
            assert a.find{it.location_kind=='ROOT'}.result_id==b.find{it.location_kind=='ROOT'}.result_id
            assert a.every{!(it.result_id in sourceIds)}
            def imgs=[];d.eachImage('source'){imgs.add(it)}
            def alias=[];d.eachImage('second'){alias.add(it)}
            assert imgs[0].result_id==alias[0].result_id
            def members=[];d.eachArchiveEntry(a.find{it.location_kind=='NESTED'}.result_id){members.add(it)}
            assert members*.filename==['leaf']
        }
    }

    @Test void skippedAndHistoricalResultsCopyWithoutBecomingCacheHits() {
        Path src=fixture('source','source',true,false),dst=basic('dest','dest')
        open(src) { d ->
            def opts=new ArchiveOptions(minFreeBytes:0,minSizeBytes:100)
            def a=new ArchiveAnalysis(d,opts);a.provider=new FixtureArchive();a.analyze('source')
            assert d.archiveStatus('source').skipped==1
            assert d.store.rows('SELECT count(*) n FROM archive_results')[0].n==3 // two old published results + skip
        }
        assert run(src,dst).rows.archive_results==3
        open(dst) { d ->
            assert d.archiveStatus('source').skipped==1
            def a=[];d.eachArchive('source'){a.add(it)}
            assert a[0].status=='SKIPPED'
        }
    }

    @Test void unfinishedGenerationsAndDanglingFeatureReferencesRefuse() {
        Path src=fixture('source','source',true,true),dst=basic('dest','dest')
        for(String feature:['archive','image']) {
            String id=rows(src,"SELECT result_id FROM ${feature}_results LIMIT 1")[0].result_id
            mutate(src,"UPDATE ${feature}_results SET state='RUNNING' WHERE result_id='${id}'")
            refusal(src,dst,'INCONSISTENT_DATABASE')
            mutate(src,"UPDATE ${feature}_results SET state='COMPLETE' WHERE result_id='${id}'")
        }
        mutate(src,"UPDATE image_entries SET filesystem_id=999")
        refusal(src,dst,'INCONSISTENT_DATABASE')
    }

    @Test void renamedDatabaseOfflineRootsUnusualPathsAndCliReportWork() {
        Path src=basic("s'ource",'one\nscan'),dst=basic('dest','two')
        mutate(src,"UPDATE scans SET root='/offline/missing/source'")
        def out=new StringWriter(),err=new StringWriter()
        int code=new CommandLine(new MergeCommand()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
            .execute('--source',src.toString(),'--destination',dst.toString(),'--quiet','--memory-limit','128MB')
        assert code==0 : err.toString()
        def report=new JsonSlurper().parseText(out.toString())
        assert report.status=='IMPORTED' && report.scan_id_map[0].name=='one\nscan'
        assert rows(dst,"SELECT root FROM scans WHERE scan_id=2")[0].root=='/offline/missing/source'
    }

    @Test void archiveCyclesAndDanglingImageDiagnosticsAreRejected() {
        Path src=fixture('source','source',true,true),dst=basic('dest','dest')
        String child=rows(src,'SELECT child_result_id FROM archive_nested')[0].child_result_id
        mutate(src,'UPDATE archive_nested SET child_result_id=parent_result_id')
        refusal(src,dst,'INCONSISTENT_DATABASE')
        mutate(src,"UPDATE archive_nested SET child_result_id='${child}'")
        mutate(src,"INSERT INTO image_errors SELECT result_id,999,NULL,'FILESYSTEM','FILE_READ_ERROR','dangling' FROM image_results")
        refusal(src,dst,'INCONSISTENT_DATABASE')
    }

    @Test void partialImageSourceChangeDiagnosticsAndFilesystemErrorsArePreserved() {
        Path src=fixture('source','source',false,true),dst=basic('dest','dest')
        mutate(src,"UPDATE scans SET phase='COMPLETE_WITH_ERRORS'; INSERT INTO scan_errors VALUES (1,'HASHING','gone','original error',123)")
        mutate(src,"DELETE FROM image_entries; DELETE FROM image_filesystems; UPDATE image_results SET state='PARTIAL',reusable=false; UPDATE image_jobs SET state='PARTIAL'")
        mutate(src,"INSERT INTO image_errors SELECT result_id,1,1,'FILESYSTEM','FILE_READ_ERROR','before mutation' FROM image_results")
        mutate(src,"INSERT INTO image_errors SELECT result_id,NULL,NULL,'CONTENT','SOURCE_CHANGED','source changed' FROM image_results")
        assert run(src,dst).status=='IMPORTED'
        assert rows(dst,"SELECT message FROM scan_errors")[0].message=='original error'
        assert rows(dst,"SELECT count(*) n FROM image_errors")[0].n==2
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void longDuckDbQueryCanBeCancelledWithoutWaitingForCompletion() {
        StopToken stop=new StopToken()
        DriverManager.getConnection('jdbc:duckdb:').withCloseable { c ->
            MergeSql q=new MergeSql(c,stop)
            def executor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            try {
                executor.scheduleWithFixedDelay({ stop.cancel(); q.cancelActive() } as Runnable,300,50,java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThrows(java.util.concurrent.CancellationException) {
                    q.scalar('SELECT sum(sqrt(a.i*b.i)) FROM range(100000) a(i) CROSS JOIN range(100000) b(i)')
                }
            } finally { executor.shutdownNow() }
        }
    }

    Path fixture(String file,String scanName,boolean archive,boolean image) {
        Path root=Files.createDirectories(work.resolve(file+'-root'))
        if(archive) Files.writeString(root.resolve('outer.7z'),'outer')
        if(image) Files.writeString(root.resolve('disk.img'),'raw')
        if(!archive && !image) Files.writeString(root.resolve('plain'),'content')
        open(db(file)) { d ->
            d.scan(scanName,root,new StopToken(),true)
            analyze(d,scanName,archive,image)
            for(String feature:['archive','image']) if(feature=='archive'?archive:image) {
                // The source registry is intentionally nonexistent. Merge must not touch it.
                d.store.exec("INSERT INTO ${feature}_temp_roots VALUES (?,?)",'/foreign/'+scanName,'foreign-owner')
            }
        }
        db(file)
    }
    static void analyze(Dedup d,String name,boolean archive,boolean image) {
        if(archive) {
            def a=new ArchiveAnalysis(d,new ArchiveOptions(minFreeBytes:0));a.provider=new FixtureArchive();a.analyze(name)
        }
        if(image) new ImageAnalysis(d.store,tuning(),new ImageOptions(minFreeBytes:0,containerHash:'always'),new StopToken(),{},new ImageTest.FakeProvider()).run(name)
    }
    static class FixtureArchive implements ArchiveProvider {
        String identity(Path w,ArchiveOptions o,StopToken s) { 'merge-fixture-v1' }
        Map extract(Path w,Map c,ArchiveOptions o,StopToken s,Closure events) {
            boolean outer=Files.readString(Path.of(c.volumes[0] as String))=='outer'
            String name=outer?'nested.7z':'leaf',data=outer?'inner':'content'
            Files.writeString(w.resolve('1'),data)
            events([event:'member',ordinal:1L,path:name,kind:'FILE',payload:'1',declared_size:(long)data.length(),actual_size:(long)data.length(),
                modified_sec:10L,modified_nano:123,integrity:'READ_OK',encrypted:false])
            [errors:0,operational:false,limited:false,reached_eof:true,volume_complete:true,temporary_bytes:(long)data.length()]
        }
    }
}
