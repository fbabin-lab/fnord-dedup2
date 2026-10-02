package fnord.dedup

import fnord.dedup.archive.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.zip.GZIPOutputStream

class ArchiveRecoveryTest {
    @TempDir Path work
    ScanOptions tuning() { new ScanOptions(batchSize:2,memoryLimit:'128MB',databaseThreads:1) }
    ArchiveOptions options() { new ArchiveOptions(minFreeBytes:0) }

    void requireNativeArchiveRuntime() {
        Path probe = Files.createDirectories(work.resolve('native-runtime-probe'))
        try {
            new NativeArchiveProvider().identity(probe, options(), new StopToken())
        } catch (IOException failure) {
            Assumptions.assumeTrue(false,
                'Native archive runtime unavailable; archive integration test skipped: ' + failure.message)
        }
    }

    @Test void integerJsonValuesAreWrittenAccordingToSqlColumnType() {
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            ArchiveStore s=new ArchiveStore(d.store,2)
            def b=s.batch()
            b.add('archive_errors',['r',1,'CONTENT','BAD','message'])
            b.add('archive_members',['r',1,'file','file','FILE',3,3,1700000000,0,'hash',null,'READ_OK',false,null,null,null,null,null])
            b.flush()
            assert d.store.rows('SELECT ordinal FROM archive_errors')[0].ordinal==1L
            assert d.store.rows('SELECT actual_size FROM archive_members')[0].actual_size==3L
        }
    }

    @Test void recoveryRemovesStagingButRetainsFinalizedChildResults() {
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            ArchiveStore s=new ArchiveStore(d.store,2)
            d.store.exec("INSERT INTO archive_results(result_id,scan_id,state) VALUES ('parent',1,'RUNNING'),('child',1,'COMPLETE')")
            def b=s.batch()
            b.add('archive_nested',['parent','child.zip',1,'child',false])
            b.add('archive_errors',['parent',1,'CONTENT','STAGED','hidden'])
            b.flush()
            boolean rejected=false
            try { s.eachMember('parent') {} } catch (IllegalStateException expected) { rejected=true }
            assert rejected
            s.recover()
            assert d.store.rows('SELECT result_id FROM archive_results')*.result_id==['child']
            assert d.store.rows('SELECT * FROM archive_nested').empty
            assert d.store.rows('SELECT * FROM archive_errors').empty
        }
    }

    @Test void cleanupHandlesInitializationAndFinalUnlinkCrashesWithoutFollowingLinks() {
        Path root=Files.createDirectory(work.resolve('input'))
        Path outside=Files.writeString(work.resolve('outside'),'preserve')
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            ArchiveStore s=new ArchiveStore(d.store,2)
            ArchiveTemp t=new ArchiveTemp(d.store,options(),s.instanceId,root)
            Path stale=t.create()
            Files.createSymbolicLink(stale.resolve('link'),outside)
            Path prep=Files.createDirectory(t.namespace.resolve('.preparing-interrupted'))
            Files.writeString(prep.resolve('.fnord-owner'),'partial')
            Path finalUnlink=Files.createDirectory(t.namespace.resolve('attempt-cleanup-interrupted'))
            Files.createFile(finalUnlink.resolve('.extract-lock'))
            ArchiveTemp resumed=new ArchiveTemp(d.store,options(),s.instanceId,root)
            assert !Files.exists(stale) && !Files.exists(prep) && !Files.exists(finalUnlink)
            assert Files.readString(outside)=='preserve'
            resumed.close()
            // t models a dead process: its live set is intentionally not closed.
        }
    }

    @Test void databaseCopiesDoNotCleanAnotherDatabasesTemporaryNamespace() {
        Path root=Files.createDirectory(work.resolve('input'))
        Path foreign=Files.createDirectory(work.resolve('foreign'))
        Files.writeString(foreign.resolve('.fnord-owner'),'another database')
        Path foreignWork=Files.createDirectory(foreign.resolve('attempt-active'))
        Files.writeString(foreignWork.resolve('data'),'preserve')
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            ArchiveStore s=new ArchiveStore(d.store,2)
            d.store.exec('INSERT INTO archive_temp_roots VALUES (?,?)',foreign.toString(),'another database')
            new ArchiveTemp(d.store,options(),s.instanceId,root).close()
        }
        assert Files.readString(foreignWork.resolve('data'))=='preserve'
    }

    @Test void operationalFailureKeepsReadableMembersAndCanBeRetried() {
        requireNativeArchiveRuntime()
        Path root=Files.createDirectory(work.resolve('input'))
        Files.write(root.resolve('one.zip'),ArchiveTest.zip(['file':'one'.bytes]))
        Files.write(root.resolve('two.zip'),ArchiveTest.zip(['file':'two'.bytes]))
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            d.scan('s',root,new StopToken(),true)
            ArchiveAnalysis analysis=new ArchiveAnalysis(d,options())
            NativeArchiveProvider nativeProvider=new NativeArchiveProvider()
            boolean inject=true
            analysis.provider=[
                identity:{Path p,ArchiveOptions o,StopToken t -> nativeProvider.identity(p,o,t)},
                extract:{Path p,Map c,ArchiveOptions o,StopToken t,Closure event ->
                    nativeProvider.extract(p,c,o,t) { Map row ->
                        event.call(row)
                        if(inject && row.event=='member') {inject=false;throw new IOException('simulated transient failure')}
                    }
                }
            ] as ArchiveProvider
            assert analysis.analyze('s').phase=='COMPLETE_WITH_ERRORS'
            assert d.archiveStatus('s').retryable_roots==1
            assert d.archiveStatus('s').checksummed_members==2
            assert d.analyzeArchives('s',options()).phase=='COMPLETE'
        }
    }

    @Test void renamedCompressedStreamsShareStableLogicalNames() {
        requireNativeArchiveRuntime()
        Path root=Files.createDirectory(work.resolve('input'))
        ByteArrayOutputStream bytes=new ByteArrayOutputStream()
        new GZIPOutputStream(bytes).withCloseable {it.write('plain stream'.bytes)}
        Files.write(root.resolve('one.gz'),bytes.toByteArray())
        Files.write(root.resolve('renamed.gz'),bytes.toByteArray())
        Dedup.open(work.resolve('db'),tuning()).withCloseable { d ->
            d.scan('s',root,new StopToken(),true)
            assert d.analyzeArchives('s',options()).duplicate_roots==1
            def rows=ArchiveTest.archives(d)
            assert ArchiveTest.members(d,rows[0].result_id)[0].relative_path=='data'
        }
    }
}
