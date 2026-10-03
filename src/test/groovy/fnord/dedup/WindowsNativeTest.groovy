package fnord.dedup

import fnord.dedup.path.*
import fnord.dedup.merge.DatabaseMerger
import com.sun.nio.file.ExtendedOpenOption
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import static org.junit.jupiter.api.Assertions.*

@EnabledOnOs(OS.WINDOWS)
class WindowsNativeTest {
    @TempDir Path work
    ScanOptions opts(){new ScanOptions(batchSize:2,databaseThreads:1,memoryLimit:'128MB')}
    static void junction(Path link,Path target){
        // Controlled generated fixtures only, never a production filename command.
        Process p=new ProcessBuilder('cmd.exe','/d','/c','mklink','/J',link.toString(),target.toString()).redirectErrorStream(true).start()
        String output=p.inputStream.getText('UTF-8');assert p.waitFor()==0:output
    }
    @Test void junctionsAreRecordedButNeverTraversedAndExplicitRootIsResolved(){
        Path root=Files.createDirectory(work.resolve('root'))
        Path outside=Files.createDirectory(work.resolve('outside'))
        Files.writeString(root.resolve('normal'),'bytes');Files.writeString(outside.resolve('secret'),'bytes')
        Path link=root.resolve('junction');junction(link,outside)
        try {
            assert NativeFiles.kind(Files.readAttributes(link,BasicFileAttributes,LinkOption.NOFOLLOW_LINKS))=='OTHER'
            Dedup.open(work.resolve('db.duckdb'),opts()).withCloseable{d ->
                def scan=d.scan('test',root)
                assert scan.files==1 && scan.other_entries==1
                assert d.store.rows("SELECT * FROM entries WHERE filename='secret'").empty
                d.scan('explicit-root',link)
                assert d.status('explicit-root').files==1
                assert d.store.scan('explicit-root').root==StoredPath.storeAbsolute(outside.toRealPath())
            }
        } finally {Files.deleteIfExists(link)}
    }
    @Test void ancestorReplacedByJunctionCannotRedirectHashing(){
        Path root=Files.createDirectory(work.resolve('root'))
        Path dir=Files.createDirectory(root.resolve('dir'))
        Path outside=Files.createDirectory(work.resolve('outside'))
        Files.writeString(dir.resolve('file'),'bytes');Files.writeString(root.resolve('file'),'bytes');Files.writeString(outside.resolve('file'),'bytes')
        Dedup.open(work.resolve('db.duckdb'),opts()).withCloseable {d ->
            d.scan('test',root,new StopToken(),true)
            Files.delete(dir.resolve('file'));Files.delete(dir);junction(dir,outside)
            try {
                def result=d.hash('test')
                assert result.hashes_completed==1 && result.errors==1
            } finally {Files.deleteIfExists(dir)}
        }
    }
    @Test void databaseSidecarsCannotBeScannedViaDifferentCase(){
        Path root=Files.createDirectory(work.resolve('input'))
        Files.writeString(root.resolve('one'),'same');Files.writeString(root.resolve('two'),'same')
        Path database=root.resolve('Scan Data.duckdb')
        Dedup.open(database,opts()).withCloseable {d ->
            assert d.store.excluded(Path.of(database.toString().toUpperCase(Locale.ROOT)))
            assert d.store.excluded(Path.of(database.toString().toUpperCase(Locale.ROOT)+'.WAL'))
            assert d.store.excluded(Path.of(database.toString().toUpperCase(Locale.ROOT)+'.TMP').resolve('spill'))
            def status=d.scan('test',Path.of(root.toString().toUpperCase(Locale.ROOT)))
            assert status.files==2 && status.hashes_completed==2
        }
        assertThrows(fnord.dedup.merge.DatabaseMergeException){new DatabaseMerger(opts()).merge(database,Path.of(database.toString().toUpperCase(Locale.ROOT)))}
    }
    @Test void lockedFileFailsWithoutBypassingWindowsSharingRules(){
        Path root=Files.createDirectory(work.resolve('input'))
        Path locked=Files.writeString(root.resolve('locked'),'same');Files.writeString(root.resolve('other'),'same')
        Dedup.open(work.resolve('db.duckdb'),opts()).withCloseable{d ->
            d.scan('test',root,new StopToken(),true)
            FileChannel.open(locked,StandardOpenOption.WRITE,ExtendedOpenOption.NOSHARE_READ).withCloseable{channel ->
                def result=d.hash('test')
                assert result.hashes_completed==1 && result.errors==1
            }
            assert d.hash('test').hashes_completed==2
        }
    }
    @Test void longPathsAreNotTruncatedAndDriveRootStaysAbsolute(){
        Path root=Files.createDirectory(work.resolve('long'))
        Path deep=root
        18.times{deep=Files.createDirectory(deep.resolve('folder-'+it+'-abcdefghijk'))}
        Files.writeString(deep.resolve('one'),'same');Files.writeString(deep.resolve('two'),'same')
        assert deep.toString().length()>260
        Dedup.open(work.resolve('db.duckdb'),opts()).withCloseable{d ->
            def status=d.scan('long',root)
            assert status.hashes_completed==2 && status.errors==0
            List rows=[];d.eachDuplicate('long'){rows.add(it)}
            assert rows.size()==2 && rows.every{it.path.length()>260 && !it.path.contains('\\')}
        }
        assert StoredPath.storeAbsolute(root.root)==root.root.toString().replace('\\','/')
    }
    @Test void ambiguousAndDeviceRootFormsAreRejected(){
        for(String value:['C:folder','C:','\\folder','\\\\.\\PhysicalDrive0','\\\\?\\Volume{123}\\']) {
            assertThrows(IllegalArgumentException){StoredPath.canonicalRoot(Path.of(value))}
        }
    }
}
