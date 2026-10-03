package fnord.dedup

import fnord.dedup.path.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import static org.junit.jupiter.api.Assertions.*

class HardenedStoredPathTest {
    @TempDir Path work
    @Test void windowsDriveAndUncEncodingDoesNotDependOnTheHost() {
        assert StoredPath.encodeWindowsAbsolute('d:\\Data\\résumé.txt')=='D:/Data/résumé.txt'
        assert StoredPath.encodeWindowsAbsolute('C:\\')=='C:/'
        assert StoredPath.encodeWindowsAbsolute('\\\\nas\\share\\photos')=='//nas/share/photos'
        assert StoredPath.encodeWindowsAbsolute('\\\\?\\C:\\long\\file')=='C:/long/file'
        assert StoredPath.encodeWindowsAbsolute('\\\\?\\UNC\\nas\\share\\long')=='//nas/share/long'
        assert StoredPath.encodeWindowsAbsolute('\\\\nas\\share\\')=='//nas/share'
        for(String p:['C:relative','C:','/data','\\data','\\\\.\\PhysicalDrive0','\\\\?\\Volume{123}\\x','C:/a/../b','C:/x:y','C:/CON','C:/x.','C:/x ','//server']) {
            assertThrows(IllegalArgumentException) { StoredPath.encodeWindowsAbsolute(p) }
        }
    }
    @Test void displayIsHostIndependentAndRelativeComponentsAreValidated() {
        assert StoredPath.display('C:/','x/y')=='C:/x/y'
        assert StoredPath.display('//host/share','x/y')=='//host/share/x/y'
        assert StoredPath.display('/','x/y')=='/x/y'
        assert StoredPath.display('/data','a\\b.txt')=='/data/a\\b.txt'
        for(String bad:['/absolute','../escape','a/../b','a//b','a/./b','a/']) {
            assertThrows(IllegalArgumentException) { StoredPath.display('/data',bad) }
        }
        assertThrows(IllegalArgumentException) { StoredPath.display('C:/Data','a\\b.txt') }
    }
    @Test void foreignRootsNeverMaterializeAgainstWorkingDirectoryOrCurrentDrive() {
        String foreign=StoredPath.windows()?'/offline/data':'Z:/offline/data'
        assertThrows(ForeignStoredPathException) { StoredPath.materialize(foreign,'file') }
        if(!StoredPath.windows()) assertThrows(ForeignStoredPathException) {StoredPath.materialize('//host/share','file')}
    }
    @Test void nativeRoundTripUsesComponentsAndKeepsUnicode() {
        Path root=Files.createDirectory(work.resolve('é space')).toRealPath()
        Path child=Files.createDirectories(root.resolve('東京')).resolve('😀.txt')
        Files.writeString(child,'same')
        String stored=StoredPath.storeAbsolute(root)
        String relative=StoredPath.storeRelative(root,child)
        assert relative=='東京/😀.txt'
        assert Files.isSameFile(child,StoredPath.materialize(stored,relative))
        assert StoredPath.storeRelative(root,root)==''
    }
    @Test void aJunctionLikeAttributeIsNeverClassifiedAsDirectory() {
        BasicFileAttributes attrs=[isSymbolicLink:{false},isOther:{true},isDirectory:{true},isRegularFile:{false}] as BasicFileAttributes
        assert NativeFiles.kind(attrs)=='OTHER'
    }
    @Test @EnabledOnOs(OS.LINUX) void literalBackslashIsNotConvertedToDirectorySeparator() {
        Path root=Files.createDirectory(work.resolve('a\\b')).toRealPath()
        Path file=Files.writeString(root.resolve('c\\d'),'bytes')
        assert StoredPath.storeAbsolute(root).endsWith('/a\\b')
        assert StoredPath.storeRelative(root,file)=='c\\d'
        assert Files.isSameFile(file,StoredPath.materialize(StoredPath.storeAbsolute(root),'c\\d'))
        Dedup.open(work.resolve('backslash.duckdb')).withCloseable { d ->
            Files.writeString(root.resolve('same'),'bytes')
            d.scan('test',root)
            List rows=[];d.eachDuplicate('test'){rows.add(it)}
            assert rows*.relative_path.toSet()==['c\\d','same'].toSet()
        }
    }
}
