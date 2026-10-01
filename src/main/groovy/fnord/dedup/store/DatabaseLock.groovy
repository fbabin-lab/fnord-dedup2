package fnord.dedup.store

import groovy.transform.CompileStatic
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Keep the sidecar file after unlock: unlinking it would allow split locks. */
@CompileStatic
final class DatabaseLock implements AutoCloseable {
    private final FileChannel channel
    private final FileLock lock

    DatabaseLock(Path path) {
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        FileLock acquired = null
        try {
            acquired = channel.tryLock()
            if (acquired == null) throw new IllegalStateException('Database is in use; stop the other command first')
        } catch (OverlappingFileLockException e) {
            channel.close()
            throw new IllegalStateException('Database is already open in this JVM', e)
        } catch (Exception e) {
            channel.close()
            throw e
        }
        lock = acquired
    }

    @Override void close() {
        try { lock.release() } finally { channel.close() }
    }
}
