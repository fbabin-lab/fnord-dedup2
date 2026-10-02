package fnord.dedup

import fnord.dedup.archive.*
import fnord.dedup.cli.Main
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @TempDir Path work
    ScanOptions scanOptions() { new ScanOptions(batchSize:2,databaseThreads:1,memoryLimit:'128MB') }
    ArchiveOptions options() { new ArchiveOptions(minFreeBytes:0,maxExpandedBytes:64L*1024*1024,maxTempBytes:128L*1024*1024) }
    Path root() { Files.createDirectories(work.resolve('input')) }
    Path database() { work.resolve('scans.duckdb') }

    void requireNativeArchiveRuntime() {
        Path probe = Files.createDirectories(work.resolve('native-runtime-probe'))
        try {
            new NativeArchiveProvider().identity(probe, options(), new StopToken())
        } catch (ArchiveRuntimeUnavailable failure) {
            Assumptions.assumeTrue(false,
                'Native archive runtime unavailable; archive integration test skipped: ' + failure.message)
        }
    }

    static byte[] zip(Map<String,byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        new ZipOutputStream(bytes).withCloseable { out ->
            files.each { String path, byte[] data ->
                ZipEntry e = new ZipEntry(path); e.setTime(1700000000000L)
                out.putNextEntry(e); out.write(data); out.closeEntry()
            }
        }
        bytes.toByteArray()
    }
    static String sha(byte[] data) { HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(data)) }
    static List<Map> archives(Dedup d,String name='s') { List<Map> rows=[];d.eachArchive(name){rows.add(it)};rows }
    static List<Map> members(Dedup d,String id) { List<Map> rows=[];d.eachArchiveEntry(id){rows.add(it)};rows }
    void cleanTemp() {
        Path parent = Path.of(database().toString()+'.archives-tmp')
        if (Files.exists(parent)) Files.walk(parent).withCloseable { paths ->
            assert paths.noneMatch { it.fileName.toString().startsWith('attempt-') }
        }
    }

    @Test void hashesUniqueSizeArchivesWithoutChangingNormalHashPhaseAndReusesCopies() {
        requireNativeArchiveRuntime()
        Path input=root()
        byte[] contents=zip(['a.txt':'hello'.bytes,'folder/b.txt':'world!!!'.bytes])
        Files.write(input.resolve('one.zip'),contents)
        Files.write(input.resolve('two.zip'),contents)
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            assert d.status('s').hashes_completed==0
            Map state=d.analyzeArchives('s',options())
            assert state.phase=='COMPLETE'
            assert state.root_archives==2
            assert state.duplicate_roots==1
            assert state.checksummed_members==2
            assert d.status('s').phase=='READY'
            def roots=archives(d).findAll { it.location_kind=='ROOT' }
            assert roots*.result_id.unique().size()==1
            def entries=members(d,roots[0].result_id)
            assert entries.find { it.filename=='a.txt' }.sha256==sha('hello'.bytes)
            assert entries.every { it.modified_sec!=null && it.actual_size>0 }
            long results=d.store.rows('SELECT count(*) AS n FROM archive_results')[0].n
            d.analyzeArchives('s',options())
            assert d.store.rows('SELECT count(*) AS n FROM archive_results')[0].n==results
        }
        cleanTemp()
        assert Files.readAllBytes(input.resolve('one.zip'))==contents
    }

    @Test void nestedDuplicatesAreSharedAndCanBeReusedAcrossNamedScans() {
        requireNativeArchiveRuntime()
        Path input=root()
        byte[] inner=zip(['file.txt':'inside'.bytes])
        Files.write(input.resolve('inner.zip'),inner)
        Files.write(input.resolve('outer.zip'),zip(['nested/inside.zip':inner,'ordinary':'x'.bytes]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            Map state=d.analyzeArchives('s',options())
            assert state.phase=='COMPLETE'
            assert state.nested_archives==1
            assert d.store.rows("SELECT count(*) AS n FROM archive_results WHERE state='COMPLETE'")[0].n==2
            def rows=archives(d)
            def nested=rows.find {it.location_kind=='NESTED'}
            assert members(d,nested.result_id).find {it.filename=='file.txt'}.sha256==sha('inside'.bytes)
            d.scan('another',input,new StopToken(),true)
            assert d.analyzeArchives('another',options()).duplicate_roots==2
            assert d.store.rows("SELECT count(*) AS n FROM archive_results WHERE state='COMPLETE'")[0].n==2
        }
        cleanTemp()
    }

    @Test void rejectsUnsafePathsButKeepsSafeFiles() {
        requireNativeArchiveRuntime()
        Path input=root()
        Files.write(input.resolve('unsafe.zip'),zip(['../escape':'no'.bytes,'/absolute':'no'.bytes,'good\nfile':'yes'.bytes]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            assert d.analyzeArchives('s',options()).phase=='COMPLETE_WITH_ERRORS'
            def entries=members(d,archives(d)[0].result_id)
            assert entries.count {it.integrity=='READ_OK'}==1
            assert entries.find {it.filename=='good\nfile'}.sha256==sha('yes'.bytes)
            assert entries.count {it.integrity=='SKIPPED'}==2
        }
        assert !Files.exists(work.resolve('escape'))
        cleanTemp()
    }

    @Test void depthLimitedResultsDoNotPoisonLaterRetry() {
        requireNativeArchiveRuntime()
        Path input=root()
        Files.write(input.resolve('outer.zip'),zip(['inside.zip':zip(['a':'a'.bytes])]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            ArchiveOptions limited=options();limited.maxDepth=0
            assert d.analyzeArchives('s',limited).errors>0
            assert d.store.rows("SELECT count(*) AS n FROM archive_results WHERE reusable")[0].n==0
            ArchiveOptions retry=options();retry.retryErrors=true
            assert d.analyzeArchives('s',retry).phase=='COMPLETE'
            assert d.archiveStatus('s').checksummed_members==2
        }
        cleanTemp()
    }

    @Test void sizeLimitsStopExtractionAndRetainPreviouslyRecoveredFiles() {
        requireNativeArchiveRuntime()
        Path input=root()
        Files.write(input.resolve('large.zip'),zip(['small':'s'.bytes,'large':new byte[4096]]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            ArchiveOptions limited=options();limited.maxExpandedBytes=100
            assert d.analyzeArchives('s',limited).phase=='COMPLETE_WITH_ERRORS'
            def result=archives(d)[0]
            assert !result.reusable
            assert members(d,result.result_id).find {it.filename=='small'}.sha256==sha('s'.bytes)
        }
        cleanTemp()
    }

    @Test void archiveChangedSinceDiscoveryIsNotAnalyzed() {
        requireNativeArchiveRuntime()
        Path input=root()
        Path file=input.resolve('changed.zip')
        Files.write(file,zip(['a':'a'.bytes]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            Files.write(file,zip(['a':'changed contents'.bytes]))
            assert d.analyzeArchives('s',options()).errors>0
            assert d.archiveStatus('s').checksummed_members==0
            assert archives(d)[0].diagnostic.contains('SOURCE_CHANGED')
        }
        cleanTemp()
    }

    @Test void emptyArchiveAndEmptyScanComplete() {
        requireNativeArchiveRuntime()
        Path input=root()
        Files.write(input.resolve('empty.zip'),zip([:]))
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            assert d.analyzeArchives('s',options()).phase=='COMPLETE'
            assert d.archiveStatus('s').indexed_members==0
            Path empty=Files.createDirectory(work.resolve('empty'))
            d.scan('empty',empty,new StopToken(),true)
            assert d.analyzeArchives('empty',options()).root_archives==0
        }
        cleanTemp()
    }

    @Test void groupingUsesNumericSlotsAndDirectoryScope() {
        assert ArchiveNames.describe('x/a.part001.rar').slot==1
        assert ArchiveNames.describe('x/a.part2.rar').group_key==ArchiveNames.describe('x/a.part10.rar').group_key
        assert ArchiveNames.describe('x/a.part1.rar').group_key!=ArchiveNames.describe('y/a.part1.rar').group_key
        List<Map> rows=[[slot:10],[slot:2],[slot:1]]
        assert ArchiveNames.arrange(rows,'rar-parts').missing
        assert rows*.slot==[1,2,10]
        assert ArchiveNames.arrange([[slot:1],[slot:1]],'rar-parts').ambiguous
        assert ArchiveNames.arrange([[slot:1]],'zip-split').missing
    }


    @Test void minimumArchiveSizeSkipsWithoutErrorsAndCanBeLoweredLater() {
        requireNativeArchiveRuntime()
        Path input=root()
        Path small=input.resolve('small.zip')
        Files.write(small,zip(['tiny.txt':'tiny'.bytes]))
        long threshold=Files.size(small)+1L
        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            ArchiveOptions limited=options(); limited.minSizeBytes=threshold
            Map state=d.analyzeArchives('s',limited)
            assert state.phase=='COMPLETE'
            assert state.skipped==1
            assert state.errors==0
            assert state.checksummed_members==0
            Map skipped=archives(d).find { it.location_kind=='ROOT' }
            assert skipped.status=='SKIPPED'
            assert members(d,skipped.result_id).empty
            assert d.store.rows('SELECT count(*) AS n FROM archive_volumes WHERE result_id=? AND sha256 IS NULL',skipped.result_id)[0].n==1

            ArchiveOptions enabled=options(); enabled.minSizeBytes=0
            Map resumed=d.analyzeArchives('s',enabled)
            assert resumed.phase=='COMPLETE'
            assert resumed.skipped==0
            assert resumed.checksummed_members==1
            Map completed=archives(d).find { it.location_kind=='ROOT' }
            assert completed.status=='COMPLETE'
            assert members(d,completed.result_id).find { it.filename=='tiny.txt' }.sha256==sha('tiny'.bytes)
        }
        cleanTemp()
    }

    @Test void minimumArchiveSizeAppliesToNestedArchivesAndMultipartUsesTotalBytes() {
        requireNativeArchiveRuntime()
        Path input=root()
        byte[] inner=zip(['deep.txt':'deep'.bytes])
        byte[] padding=new byte[8192]
        new Random(42L).nextBytes(padding)
        Path outer=input.resolve('outer.zip')
        Files.write(outer,zip(['inside.zip':inner,'padding.bin':padding]))
        assert Files.size(outer)>inner.length
        long threshold=inner.length+1L

        Dedup.open(database(),scanOptions()).withCloseable { d ->
            d.scan('s',input,new StopToken(),true)
            ArchiveOptions limited=options(); limited.minSizeBytes=threshold
            Map state=d.analyzeArchives('s',limited)
            assert state.phase=='COMPLETE'
            assert state.skipped==0
            assert state.errors==0
            assert state.nested_archives==1
            Map nested=archives(d).find { it.location_kind=='NESTED' }
            assert nested.status=='SKIPPED'
            assert members(d,nested.result_id).empty
            assert members(d,archives(d).find { it.location_kind=='ROOT' }.result_id).find { it.filename=='inside.zip' }.sha256==sha(inner)
        }

        Path multi=Files.createDirectories(work.resolve('multi'))
        byte[] first=zip(['a.txt':'a'.bytes])
        Files.write(multi.resolve('set.zip.001'),first)
        Files.write(multi.resolve('set.zip.002'),new byte[128])
        long combined=Files.size(multi.resolve('set.zip.001'))+Files.size(multi.resolve('set.zip.002'))
        Dedup.open(work.resolve('multi.duckdb'),scanOptions()).withCloseable { d ->
            d.scan('m',multi,new StopToken(),true)
            ArchiveOptions limited=options(); limited.minSizeBytes=combined+1L
            Map state=d.analyzeArchives('m',limited)
            assert state.skipped==1
            Map skipped=archives(d).find { it.location_kind=='ROOT' }
            assert skipped.status=='SKIPPED'
            assert d.store.rows('SELECT sum(size) AS n FROM archive_volumes WHERE result_id=?',skipped.result_id)[0].n==combined
        }
        cleanTemp()
    }

    @Test void archiveCommandsAreRegisteredAndMachineReadable() {
        requireNativeArchiveRuntime()
        Path input=root()
        Files.write(input.resolve('a.zip'),zip(['file':'bytes'.bytes]))
        Dedup.open(database(),scanOptions()).withCloseable { it.scan('s',input,new StopToken(),true) }
        StringWriter out=new StringWriter(),err=new StringWriter()
        def cli=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
        int code=cli.execute(['--db',database().toString(),'archives','--name','s','--archive-temp-min-free','0','--archive-min-size-bytes','0','--quiet'] as String[])
        assert code==0 : err.toString()
        assert new JsonSlurper().parseText(out.toString()).phase=='COMPLETE'
        out.buffer.setLength(0)
        code=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(['--db',database().toString(),'archive-list','--name','s'] as String[])
        assert code==0 : err.toString()
        assert new JsonSlurper().parseText(out.toString().trim()).location_kind=='ROOT'
        cleanTemp()
    }
}
