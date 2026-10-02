package fnord.dedup

import fnord.dedup.path.StoredPath
import fnord.dedup.path.ForeignStoredPathException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.assertThrows

class StoredPathTest {
    @TempDir Path work
    @Test void classifiesPortableRootsWithoutConsultingHostProvider() {
        assert StoredPath.classify('/') == StoredPath.Style.POSIX
        assert StoredPath.classify('/data/photos') == StoredPath.Style.POSIX
        assert StoredPath.classify('C:/') == StoredPath.Style.WINDOWS_DRIVE
        assert StoredPath.classify('d:/Data/Photos') == StoredPath.Style.WINDOWS_DRIVE
        assert StoredPath.classify('//server/share') == StoredPath.Style.WINDOWS_UNC
        assert StoredPath.classify('//server/share/folder') == StoredPath.Style.WINDOWS_UNC
        assert StoredPath.classify('C:\\Data') == StoredPath.Style.UNKNOWN
        assert StoredPath.classify('relative/path') == StoredPath.Style.UNKNOWN
    }
    @Test void displayJoinIsPortableAndDoesNotUseHostPathSemantics() {
        assert StoredPath.join('C:/', 'a/b.txt') == 'C:/a/b.txt'
        assert StoredPath.join('C:/Data', 'a/b.txt') == 'C:/Data/a/b.txt'
        assert StoredPath.join('//nas/share', 'a/b.txt') == '//nas/share/a/b.txt'
        assert StoredPath.join('/data', 'a/b.txt') == '/data/a/b.txt'
        assert StoredPath.join('/data', '') == '/data'
    }
    @Test void linuxLiteralBackslashRemainsOneFilenameComponent() {
        Assumptions.assumeFalse(StoredPath.windowsHost())
        Path relative = Path.of('a\\b.txt')
        assert relative.nameCount == 1
        assert StoredPath.storeRelative(relative) == 'a\\b.txt'
        Path root = Files.createDirectory(work.resolve('root'))
        Path actual = StoredPath.resolve(root, 'a\\b.txt')
        assert actual.fileName.toString() == 'a\\b.txt'
    }
    @Test void currentHostAbsoluteSerializationRoundTripsWithoutChangingComponents() {
        Path root = work.toRealPath()
        String stored = StoredPath.storeAbsolute(root)
        Path reopened = StoredPath.nativeRoot(stored)
        assert Files.isSameFile(root, reopened)
        assert StoredPath.storeAbsolute(reopened) == stored
    }
    @Test void foreignPlatformRootsAreNotReinterpreted() {
        if (StoredPath.windowsHost()) assertThrows(ForeignStoredPathException) { StoredPath.nativeRoot('/data/photos') }
        else {
            assertThrows(ForeignStoredPathException) { StoredPath.nativeRoot('C:/Data/Photos') }
            assertThrows(ForeignStoredPathException) { StoredPath.nativeRoot('//server/share/photos') }
        }
    }
    @Test void relativeResolutionRejectsTraversalAndAbsoluteForms() {
        Path root = work.toRealPath()
        assertThrows(IllegalArgumentException) { StoredPath.resolve(root, '../escape') }
        assertThrows(IllegalArgumentException) { StoredPath.resolve(root, 'a//b') }
        assertThrows(IllegalArgumentException) { StoredPath.resolve(root, '/absolute') }
    }
}
