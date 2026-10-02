package fnord.dedup

import fnord.dedup.image.*
import fnord.dedup.cli.Main
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import static org.junit.jupiter.api.Assertions.*

class ImageTest {
    @TempDir Path work
    ScanOptions so() { new ScanOptions(batchSize:2,memoryLimit:'128MB',databaseThreads:1) }
    ImageOptions io() { new ImageOptions(minFreeBytes:0,containerHash:'always') }
    Path input() { Files.createDirectories(work.resolve('input')) }
    Dedup engine() { Dedup.open(work.resolve('db.duckdb'),so()) }
    static String sha(String text) { HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(text.bytes)) }
    static Map entry(long id=1L,String path='/same') {
        [event:'entry',filesystem_id:1L,entry_id:id,relative_path:path,filename:path.substring(1),kind:'FILE',size:4L,actual_size:4L,
         modified_sec:123L,modified_nano:456L,sha256:sha('same'),integrity:'READ_OK']
    }
    static class FakeProvider implements DiskImageProvider {
        int calls=0
        Closure body
        String identity(Path w,ImageOptions o,StopToken s) { 'test-native-v1' }
        Map probe(Path w,Map c,ImageOptions o,StopToken s) { [virtual_size:1048576L] }
        Map inspect(Path w,Map c,ImageOptions o,StopToken s,Closure events) {
            calls++
            if (body) return body.call(w,c,o,s,events)
            events.call([event:'filesystem',filesystem_id:1L,device:'/dev/sda',type:'ext4',uuid:null,label:null,size_bytes:1048576L,state:'COMPLETE'])
            events.call(entry())
            [entries:1L,errors:0L,retryable:false]
        }
    }
    Map analyze(Dedup d,FakeProvider p,ImageOptions o=io(),StopToken s=new StopToken(),Closure progress={}) {
        new ImageAnalysis(d.store,so(),o,s,progress,p).run('test')
    }
    @Test void hashesMembersAndReusesIdenticalContainersWithoutNormalHashes() {
        Files.writeString(input().resolve('a.img'),'raw bytes')
        Files.copy(input().resolve('a.img'),input().resolve('b.img'))
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider()
            Map status=analyze(d,p)
            assert status.phase=='COMPLETE' && status.duplicates==1 && p.calls==1
            assert d.status('test').hashes_completed==0
            List<Map> images=[];d.eachImage('test') { images.add(it) }
            assert images.size()==2 && images*.result_id.toSet().size()==1
            List<Map> entries=[];d.eachImageEntry(images[0].result_id) { entries.add(it) }
            assert entries[0].sha256==sha('same') && entries[0].modified_nano==456
            analyze(d,p); assert p.calls==1
        }
    }
    @Test void immutableCacheWorksAcrossNamedScans() {
        Files.writeString(input().resolve('a.raw'),'bytes')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider();analyze(d,p)
            d.scan('second',input(),new StopToken(),true)
            assert new ImageAnalysis(d.store,so(),io(),new StopToken(),{},p).run('second').duplicates==1
            assert p.calls==1
        }
    }
    @Test void cancellationHidesAndReplaysBatches() {
        Files.writeString(input().resolve('disk.dd'),'bytes')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def stop=new StopToken()
            def p=new FakeProvider(body:{w,c,o,s,e ->
                e.call(entry(1L));e.call(entry(2L,'/other'));s.cancel();s.check()
            })
            assert analyze(d,p,io(),stop).phase=='PAUSED'
            String id=d.store.rows('SELECT result_id FROM image_results')[0].result_id
            assert d.store.rows('SELECT count(*) AS n FROM image_entries')[0].n==2
            assertThrows(IllegalArgumentException) { d.eachImageEntry(id) {} }
        }
        engine().withCloseable { d ->
            assert analyze(d,new FakeProvider()).phase=='COMPLETE'
            assert d.store.rows('SELECT count(*) AS n FROM image_results')[0].n==1
            assert d.store.rows('SELECT count(*) AS n FROM image_entries')[0].n==1
        }
    }
    @Test void corruptMemberNeverGetsConfirmedHashAndSiblingsSurvive() {
        Files.writeString(input().resolve('disk.img'),'bytes')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider(body:{w,c,o,s,e ->
                e.call(entry())
                e.call([event:'error',filesystem_id:1L,entry_id:2L,category:'FILESYSTEM',code:'FILE_READ_ERROR',message:'bad sector'])
                e.call(entry(2L,'/broken')+[actual_size:null,sha256:null,integrity:'UNREADABLE'])
                [entries:2L,errors:1L]
            })
            Map result=analyze(d,p);assert result.phase=='COMPLETE_WITH_ERRORS' && result.hashes_completed==1
            assert d.store.rows('SELECT reusable FROM image_results')[0].reusable==false
        }
    }
    @Test void sourceMutationInvalidatesWholeGeneration() {
        Files.writeString(input().resolve('disk.raw'),'initial')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider(body:{w,c,o,s,e ->
                e.call(entry());Files.writeString(input().resolve('disk.raw'),'different content')
                [entries:1L,errors:0L]
            })
            assert analyze(d,p).phase=='COMPLETE_WITH_ERRORS'
            assert d.store.rows('SELECT count(*) AS n FROM image_entries')[0].n==0
            assert d.store.rows('SELECT code FROM image_errors')[0].code=='SOURCE_CHANGED'
        }
    }
    @Test void nativeFailureStillInvalidatesChangedSource() {
        Files.writeString(input().resolve('disk.raw'),'initial')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider(body:{w,c,o,s,e ->
                e.call(entry());e.call(entry(2L,'/other'))
                Files.writeString(input().resolve('disk.raw'),'modified before native failure')
                throw new IOException('decoder stopped')
            })
            assert analyze(d,p).hashes_completed==0
            assert d.store.rows('SELECT code FROM image_errors')[0].code=='SOURCE_CHANGED'
        }
    }
    @Test void rawNeverAutodetectsEmbeddedImageHeader() {
        Files.write(input().resolve('disk.raw'),qcow('/etc/passwd'))
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider(body:{w,c,o,s,e ->
                assert c.graph.driver=='raw' && !c.graph.containsKey('backing')
                [entries:0L,errors:0L]
            })
            analyze(d,p);assert p.calls==1
        }
    }
    static byte[] qcow(String backing) {
        byte[] bytes=new byte[512]
        ByteBuffer b=ByteBuffer.wrap(bytes)
        b.putInt(0,0x514649fb);b.putInt(4,3);b.putLong(8,128L);b.putInt(16,backing.bytes.length);b.putInt(20,9);b.putInt(100,104)
        b.putInt(104,(int)0xe2792aca);b.putInt(108,3)
        System.arraycopy('raw'.bytes,0,bytes,112,3)
        System.arraycopy(backing.bytes,0,bytes,128,backing.bytes.length)
        bytes
    }
    @Test void dependenciesRejectAbsolutePathsSymlinksMissingFilesAndCyclesBeforeNativeOpen() {
        Files.write(input().resolve('absolute.qcow2'),qcow('/etc/passwd'))
        Files.write(input().resolve('missing.qcow2'),qcow('no-such.raw'))
        Files.write(input().resolve('cycle.qcow2'),qcow('cycle.qcow2'))
        Files.writeString(work.resolve('outside.raw'),'outside')
        Files.createSymbolicLink(input().resolve('link.raw'),work.resolve('outside.raw'))
        Files.write(input().resolve('linked.qcow2'),qcow('link.raw'))
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider();assert analyze(d,p).phase=='COMPLETE_WITH_ERRORS';assert p.calls==0
            Set codes=d.store.rows('SELECT code FROM image_errors')*.code.toSet()
            assert codes.containsAll(['UNSAFE_DEPENDENCY','MISSING_COMPONENT','DEPENDENCY_CYCLE'])
        }
    }
    @Test void backingBytesArePartOfFingerprint() {
        Files.createDirectories(input().resolve('a'));Files.createDirectories(input().resolve('b'))
        for (String dir : ['a','b']) {
            Files.write(input().resolve(dir+'/disk.qcow2'),qcow('base.bin'))
            Files.writeString(input().resolve(dir+'/base.bin'),dir=='a' ? 'aaaa' : 'bbbb')
        }
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider();assert analyze(d,p).duplicates==0;assert p.calls==2
            assert d.store.rows('SELECT fingerprint FROM image_results')*.fingerprint.toSet().size()==2
        }
    }
    @Test void unrecognizedProviderDataFailsRatherThanTrustingDamagedHash() {
        assertThrows(IOException) { ImageAnalysis.validateEntry(entry()+[integrity:'DAMAGED']) }
        assertThrows(IOException) { ImageAnalysis.validateEntry(entry()+[actual_size:2L]) }
        assertThrows(IOException) { ImageAnalysis.validateEntry(entry()+[sha256:null]) }
    }
    @Test void optionsAndIndependentCliAreValidated() {
        assertThrows(IllegalArgumentException) { new ImageOptions(containerHash:'fast').validate() }
        assertThrows(IllegalArgumentException) { new ImageOptions(maxComponents:0).validate() }
        for (String command : ['images','image-status','image-list','image-entries','image-filesystems','image-partitions','image-components','image-errors']) {
            StringWriter out=new StringWriter()
            assert Main.commandLine().setOut(new PrintWriter(out)).execute(command,'--help')==0
            assert out.toString().contains(command)
        }
    }
    @Test void refusesUnfinishedFilesystemDiscoveryAndTempInsideRoot() {
        engine().withCloseable { d ->
            d.createScan('test',input())
            assertThrows(IllegalStateException) { analyze(d,new FakeProvider()) }
            d.discover('test')
            assertThrows(IllegalArgumentException) { analyze(d,new FakeProvider(),new ImageOptions(tempDirectory:input().resolve('temp'))) }
        }
    }
    @Test void unknownBackingFormatIsRejectedRatherThanGuessingRaw() {
        byte[] header=qcow('base.bin');Arrays.fill(header,104,128,(byte)0)
        Files.write(input().resolve('disk.qcow2'),header)
        Files.writeString(input().resolve('base.bin'),'opaque backing')
        engine().withCloseable { d ->
            d.scan('test',input(),new StopToken(),true)
            def p=new FakeProvider();assert analyze(d,p).phase=='COMPLETE_WITH_ERRORS';assert p.calls==0
            assert d.store.rows('SELECT code FROM image_errors')[0].code=='UNSPECIFIED_BACKING_FORMAT'
        }
    }
    @Test void progressStateChangesAreNotHiddenByTimeThrottling() {
        Main main=new Main();StringWriter err=new StringWriter()
        def cli=Main.commandLine(main).setErr(new PrintWriter(err))
        cli.parseArgs('--db',work.resolve('progress.duckdb').toString(),'--memory-limit','128MB')
        main.withEngine { d ->
            d.progress([stage:'image',state:'OPENING'])
            d.progress([stage:'image',state:'INSPECTING'])
            d.progress([stage:'image',state:'SCANNING'])
        }
        assert err.toString().readLines().size()==3
    }
}
