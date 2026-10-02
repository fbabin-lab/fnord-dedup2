package fnord.dedup

import fnord.dedup.cross.CrossScanOptions
import fnord.dedup.hash.FileHasher
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PortableDatabasePathTest {
    @TempDir Path work
    ScanOptions options() { new ScanOptions(batchSize:4,databaseThreads:1,memoryLimit:'128MB') }
    static Path write(Path root,String name,String text='same') { Files.writeString(root.resolve(name),text) }

    @Test void foreignWindowsRootsRemainReportableWhenHashesAlreadyExist() {
        Assumptions.assumeFalse(fnord.dedup.path.StoredPath.windowsHost())
        Path a=Files.createDirectory(work.resolve('a')), b=Files.createDirectory(work.resolve('b'))
        write(a,'a1');write(a,'a2');write(b,'b1');write(b,'b2')
        Path db=work.resolve('portable.duckdb')
        Dedup.open(db,options()).withCloseable { Dedup engine ->
            engine.scan('A',a);engine.scan('B',b)
            engine.store.exec("UPDATE scans SET root='C:/Offline/A' WHERE name='A'")
            engine.store.exec("UPDATE scans SET root='D:/Offline/B' WHERE name='B'")
            engine.hasher=[algorithm:{'SHA-256'},hash:{ Path p,StopToken stop,int n -> throw new AssertionError('foreign root was opened') }] as FileHasher
            List<Map> cross=[]
            Map summary=engine.crossDuplicates(['A','B'],new CrossScanOptions(),new StopToken()) { cross.add(it) }
            assert summary.phase=='COMPLETE' && summary.hashes_needed==0
            assert cross.size()==4
            assert cross.findAll {it.scan_name=='A'}.every { (it.path as String).startsWith('C:/Offline/A/') }
            List<Map> intra=[]; engine.eachDuplicate('A') { intra.add(it) }
            assert intra.size()==2 && intra.every { (it.path as String).startsWith('C:/Offline/A/') }
        }
    }
    @Test void missingHashOnForeignRootIsUnresolvedWithoutPathReinterpretation() {
        Assumptions.assumeFalse(fnord.dedup.path.StoredPath.windowsHost())
        Path a=Files.createDirectory(work.resolve('a')), b=Files.createDirectory(work.resolve('b'))
        write(a,'a');write(b,'b')
        Path db=work.resolve('foreign.duckdb')
        Dedup.open(db,options()).withCloseable { Dedup engine ->
            engine.scan('A',a,new StopToken(),true);engine.scan('B',b,new StopToken(),true)
            engine.store.exec("UPDATE scans SET root='C:/Offline/A' WHERE name='A'")
            List<Map> errors=[]
            Map summary=engine.crossDuplicates(['A','B'],new CrossScanOptions(onError:{ errors.add(it) }),new StopToken()) { }
            assert summary.phase=='COMPLETE_WITH_ERRORS'
            assert summary.hash_failures==1 && summary.hashes_completed_this_run==1
            assert errors*.code==['FOREIGN_ROOT']
        }
    }
}
