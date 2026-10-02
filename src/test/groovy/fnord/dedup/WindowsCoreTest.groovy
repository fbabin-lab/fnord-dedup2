package fnord.dedup

import fnord.dedup.cross.CrossScanOptions
import fnord.dedup.merge.DatabaseMerger
import fnord.dedup.path.StoredPath
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WindowsCoreTest {
    @TempDir Path work
    @BeforeEach void windowsOnly() { Assumptions.assumeTrue(StoredPath.windowsHost(), 'Windows-only core portability test') }
    ScanOptions options() { new ScanOptions(batchSize:4,directoryBatchSize:2,workers:1,databaseThreads:1,memoryLimit:'128MB') }
    static Path write(Path root,String relative,String text) {
        Path target=root.resolve(relative); Files.createDirectories(target.parent); Files.writeString(target,text); target
    }
    @Test void scanHashDuplicateAndCrossScanPersistPortablePaths() {
        Path rootA=Files.createDirectory(work.resolve('Root A é')), rootB=Files.createDirectory(work.resolve('Root B 東京'))
        write(rootA,'folder one\\same-a.txt','same'); write(rootA,'folder two\\same-b.txt','same'); write(rootB,'other folder\\copy.txt','same')
        Path db=work.resolve('database folder').resolve('scans.duckdb')
        Dedup.open(db,options()).withCloseable { Dedup engine ->
            engine.scan('Windows A',rootA,new StopToken(),true); engine.scan('Windows B',rootB,new StopToken(),true)
            assert engine.listScans().every { StoredPath.classify(it.root as String) in [StoredPath.Style.WINDOWS_DRIVE,StoredPath.Style.WINDOWS_UNC] && !(it.root as String).contains('\\') }
            assert engine.store.rows("SELECT relative_path FROM entries").every { !((it.relative_path ?: '') as String).contains('\\') }
            Map summary=engine.crossDuplicates(['Windows A','Windows B'],new CrossScanOptions(),new StopToken()) { }
            assert summary.phase=='COMPLETE' && summary.hashes_completed_this_run==3
            assert engine.status('Windows A').phase=='READY' && engine.status('Windows B').phase=='READY'
            List<Map> duplicates=[]; engine.eachDuplicate('Windows A',true) { duplicates.add(it) }
            assert duplicates.size()==2 && duplicates.every { !(it.path as String).contains('\\') }
        }
    }
    @Test void directoryJunctionIsRecordedButNotTraversed() {
        Path root=Files.createDirectory(work.resolve('junction root')), outside=Files.createDirectory(work.resolve('junction outside'))
        write(outside,'secret.txt','secret')
        Path junction=root.resolve('outside-junction')
        Process process=new ProcessBuilder('cmd.exe','/c','mklink','/J',junction.toString(),outside.toString()).redirectErrorStream(true).start()
        String output=process.inputStream.getText('UTF-8'); process.waitFor()
        Assumptions.assumeTrue(process.exitValue()==0,'Junction creation unavailable: '+output)
        Dedup.open(work.resolve('junction.duckdb'),options()).withCloseable { Dedup engine ->
            engine.scan('junctions',root,new StopToken(),true)
            List<Map> rows=engine.store.rows("SELECT relative_path,kind FROM entries WHERE scan_id=(SELECT scan_id FROM scans WHERE name='junctions') ORDER BY entry_id")
            assert rows.find {it.relative_path=='outside-junction'}?.kind != 'DIRECTORY'
            assert !rows.any { (it.relative_path as String).startsWith('outside-junction/') }
        }
    }
    @Test void databaseAndSidecarsInsideRootAreExcluded() {
        Path root=Files.createDirectory(work.resolve('root with database')); write(root,'ordinary.txt','content')
        Dedup.open(root.resolve('scan.duckdb'),options()).withCloseable { Dedup engine ->
            engine.scan('inside',root,new StopToken(),true)
            List<String> names=engine.store.rows("SELECT filename FROM entries WHERE scan_id=(SELECT scan_id FROM scans WHERE name='inside')")*.filename
            assert 'ordinary.txt' in names
            assert !names.any { (it as String).toLowerCase(Locale.ROOT).startsWith('scan.duckdb') }
        }
    }
    @Test void databaseMergePreservesPortableWindowsRoots() {
        Path srcRoot=Files.createDirectory(work.resolve('source root')), dstRoot=Files.createDirectory(work.resolve('destination root'))
        write(srcRoot,'source.txt','one'); write(dstRoot,'destination.txt','two')
        Path source=work.resolve('source db').resolve('source.duckdb'), destination=work.resolve('destination db').resolve('destination.duckdb')
        Dedup.open(source,options()).withCloseable { it.scan('Source scan',srcRoot,new StopToken(),true) }
        Dedup.open(destination,options()).withCloseable { it.scan('Destination scan',dstRoot,new StopToken(),true) }
        String storedSourceRoot
        Dedup.open(source,options()).withCloseable { storedSourceRoot=it.store.scan('Source scan').root as String }
        new DatabaseMerger(options()).merge(source,destination,false)
        Dedup.open(destination,options()).withCloseable { Dedup engine ->
            assert engine.store.scan('Source scan').root==storedSourceRoot
            assert !(storedSourceRoot.contains('\\'))
            assert engine.listScans()*.name.toSet()==['Destination scan','Source scan'] as Set
        }
    }
}
