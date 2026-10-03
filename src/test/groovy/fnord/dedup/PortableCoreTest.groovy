package fnord.dedup

import fnord.dedup.path.*
import fnord.dedup.merge.DatabaseMerger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.*
import java.nio.file.*
import static org.junit.jupiter.api.Assertions.*

/** Native on both OSes; foreign observations never require the original source filesystem. */
class PortableCoreTest {
    @TempDir Path work
    ScanOptions opts() {new ScanOptions(batchSize:2,memoryLimit:'128MB',databaseThreads:1)}
    Path root(String name) {
        Path p=Files.createDirectory(work.resolve(name));Files.writeString(p.resolve('résumé.txt'),'same');Files.writeString(p.resolve('東京 😀.txt'),'same');p
    }
    @Test void portableInventoryReportsAndCrossScanMergeWorkNatively() {
        Path a=work.resolve('source data.duckdb'),b=work.resolve('master data.duckdb')
        Dedup.open(a,opts()).withCloseable{d ->d.scan('source',root('first source'),new StopToken(),true)}
        Dedup.open(b,opts()).withCloseable{d ->d.scan('dest',root('second source'),new StopToken(),true)}
        assert new DatabaseMerger(opts()).merge(a,b).status=='IMPORTED'
        Dedup.open(b,opts()).withCloseable {d ->
            List rows=[];def status=d.crossDuplicates(['source','dest']) {rows.add(it)}
            assert status.hashes_completed_this_run==4 && rows.size()==4
            assert rows.every{it.path==StoredPath.display(it.scan_root,it.relative_path)}
            assert d.listScans()*.phase==['READY','READY']
            if(StoredPath.windows()) assert rows.every{!it.path.contains('\\')}
        }
    }
    @Test void sameSizeChangesAreUnresolvedUsingFilesystemTimestampPrecision() {
        Dedup.open(work.resolve('mutate.duckdb'),opts()).withCloseable {d ->
            Path a=root('old'),b=root('new')
            d.scan('A',a,new StopToken(),true);d.scan('B',b,new StopToken(),true)
            Path changed=a.resolve('résumé.txt')
            def old=Files.getLastModifiedTime(changed).toInstant()
            Files.writeString(changed,'diff')
            Files.setLastModifiedTime(changed,java.nio.file.attribute.FileTime.from(old.plusSeconds(2)))
            def r=d.crossDuplicates(['A','B']){}
            assert r.partial && r.hash_failures==1 && r.error_samples[0].code=='SOURCE_CHANGED'
            assert d.listScans()*.phase==['READY','READY']
        }
    }
    @Test void foreignSourceReportsAndNewHashesHaveDifferentAccessRules() {
        Path db=work.resolve('foreign.duckdb')
        String foreign=StoredPath.windows()?'/nonexistent/unix':'X:/nonexistent/windows'
        Dedup.open(db,opts()).withCloseable {d ->
            d.scan('old',root('first'));d.scan('new',root('second'),new StopToken(),true)
            d.store.exec('UPDATE scans SET root=? WHERE name=?',foreign,'old')
            List reported=[];d.eachDuplicate('old'){reported.add(it)}
            assert reported.size()==2 && reported.every{it.path.startsWith(foreign+'/')}
            List rows=[];def result=d.crossDuplicates(['old','new']) {rows.add(it)}
            assert result.hashes_completed_this_run==2 && !result.partial && rows.size()==4
            d.store.exec("DELETE FROM hashes WHERE scan_id=(SELECT scan_id FROM scans WHERE name='old')")
            def failed=d.crossDuplicates(['old','new']) {}
            assert failed.hash_failures==2 && failed.error_samples.every{it.code=='FOREIGN_ROOT'}
            assert d.status('old').phase=='COMPLETE'
        }
    }
    @Test void foreignDiscoveryRefusesBeforeCheckpointRecovery() {
        Dedup.open(work.resolve('db.duckdb'),opts()).withCloseable {d ->
            d.createScan('old',root('source'))
            d.store.exec('UPDATE scans SET root=?,active_dir=1',StoredPath.windows()?'/foreign':'C:/foreign')
            def before=d.store.rows('SELECT * FROM scans')
            assertThrows(ForeignStoredPathException){d.discover('old')}
            assert d.store.rows('SELECT * FROM scans')==before
        }
    }
    @Test void mergePreservesDriveUncAndPosixRootsWithoutLocalAccess() {
        Path a=work.resolve('a.duckdb'),b=work.resolve('b.duckdb')
        Dedup.open(a,opts()).withCloseable {d ->
            for(String name:['posix','drive','unc']) d.scan(name,root(name),new StopToken(),true)
            d.store.exec("UPDATE scans SET root=CASE name WHEN 'posix' THEN '/offline/data' WHEN 'drive' THEN 'D:/Offline/Data' ELSE '//host/share/data' END")
        }
        Dedup.open(b,opts()).close()
        assert new DatabaseMerger(opts()).merge(a,b).scans_imported==3
        Dedup.open(b,opts()).withCloseable {d ->
            assert d.listScans()*.root.toSet()==['/offline/data','D:/Offline/Data','//host/share/data'].toSet()
        }
    }
    @Test @EnabledOnOs(OS.WINDOWS) void archivesAndImagesRefuseBeforeOptionalSchemasAreCreated() {
        Dedup.open(work.resolve('core.duckdb'),opts()).withCloseable {d ->
            d.scan('core',root('source'))
            assertThrows(UnsupportedOperationException) {d.analyzeArchives('core')}
            assertThrows(UnsupportedOperationException) {d.analyzeImages('core')}
            assert d.store.rows("SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'archive_%' OR table_name LIKE 'image_%'").empty
        }
    }
}
