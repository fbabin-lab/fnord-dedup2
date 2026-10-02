package fnord.dedup.hash

import fnord.dedup.StopToken
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Shared worker-only observation validation. No JDBC, lifecycle changes or console output. */
final class FileHashTask {
    static Map calculate(Map candidate, Path root, FileHasher hasher, StopToken stop, int bufferBytes) {
        Map result = [entry_id:candidate.entry_id, relative_path:candidate.relative_path]
        try {
            stop.check()
            Path relative = Path.of(candidate.relative_path as String)
            Path path = root.resolve(relative).normalize()
            if (relative.isAbsolute() || !path.startsWith(root.normalize())) {
                throw new IllegalArgumentException('Inventory path escapes its scan root')
            }
            verify(path, candidate)
            HashValue value = hasher.hash(path, stop, bufferBytes)
            stop.check()
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
        if (!attributes.isRegularFile()) {
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
