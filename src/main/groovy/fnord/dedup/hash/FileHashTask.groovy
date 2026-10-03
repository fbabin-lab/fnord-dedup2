package fnord.dedup.hash

import fnord.dedup.StopToken
import fnord.dedup.path.StoredPath
import fnord.dedup.path.NativeFiles
import fnord.dedup.path.ForeignStoredPathException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Shared worker-only observation validation. No JDBC, lifecycle changes or console output. */
final class FileHashTask {
    static Map calculate(Map candidate, String storedRoot, FileHasher hasher, StopToken stop, int bufferBytes) {
        try {
            return calculate(candidate, StoredPath.nativeRoot(storedRoot), hasher, stop, bufferBytes)
        } catch (ForeignStoredPathException e) {
            return [entry_id:candidate.entry_id, relative_path:candidate.relative_path, code:'FOREIGN_ROOT', error:e.message]
        }
    }

    static Map calculate(Map candidate, Path root, FileHasher hasher, StopToken stop, int bufferBytes) {
        Map result = [entry_id:candidate.entry_id, relative_path:candidate.relative_path, existing_sha256:candidate.existing_sha256]
        try {
            stop.check()
            Path path = StoredPath.resolve(root, candidate.relative_path as String)
            NativeFiles.checkAncestors(root, path)
            verify(path, candidate)
            HashValue value = hasher.hash(path, stop, bufferBytes)
            stop.check()
            NativeFiles.checkAncestors(root, path)
            verify(path, candidate)
            if (value == null) throw new IllegalArgumentException('Hasher returned no result')
            if (value.bytesRead != (candidate.size as long)) {
                throw new FileObservationChanged('SOURCE_CHANGED', 'File length changed while hashing')
            }
            if (!(value.hex ==~ /[0-9a-f]{64}/)) throw new IllegalArgumentException('Hasher returned an invalid SHA-256 digest')
            result.sha256 = value.hex
        } catch (CancellationException ignored) {
            result.cancelled = true
        } catch (IOException | SecurityException e) {
            if (stop.cancelled) result.cancelled = true
            else {
                result.code = e instanceof FileObservationChanged ? e.code :
                    e instanceof NoSuchFileException ? 'FILE_MISSING' :
                    (e instanceof AccessDeniedException || e instanceof SecurityException) ? 'PERMISSION_DENIED' : 'READ_ERROR'
                result.error = e.toString()
            }
        }
        result
    }

    private static void verify(Path path, Map candidate) {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
        if (NativeFiles.kind(attributes) != 'FILE') {
            throw new FileObservationChanged('NOT_REGULAR_ANYMORE', 'File is no longer regular; excluded from duplicate results')
        }
        def modified = attributes.lastModifiedTime().toInstant()
        if (attributes.size() != (candidate.size as long) ||
            modified.epochSecond != (candidate.modified_sec as long) || modified.nano != (candidate.modified_nano as int)) {
            throw new FileObservationChanged('SOURCE_CHANGED', 'File changed since discovery; excluded from duplicate results. Create a new scan to refresh metadata.')
        }
    }
}

class FileObservationChanged extends IOException {
    final String code
    FileObservationChanged(String code, String message) { super(message); this.code = code }
}
