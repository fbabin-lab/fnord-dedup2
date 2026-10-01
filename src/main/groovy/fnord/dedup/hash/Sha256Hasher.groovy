package fnord.dedup.hash

import fnord.dedup.StopToken
import groovy.transform.CompileStatic
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/** No per-file subprocess; reuse each worker's buffer and digest implementation. */
@CompileStatic
final class Sha256Hasher implements FileHasher {
    private final ThreadLocal<byte[]> buffers = new ThreadLocal<byte[]>()
    private final ThreadLocal<MessageDigest> digests = ThreadLocal.withInitial {
        MessageDigest.getInstance('SHA-256')
    }

    @Override String algorithm() { 'SHA-256' }

    @Override
    HashValue hash(Path path, StopToken stop, int bufferBytes) {
        byte[] bytes = buffers.get()
        if (bytes == null || bytes.length != bufferBytes) {
            bytes = new byte[bufferBytes]
            buffers.set(bytes)
        }
        MessageDigest digest = digests.get()
        digest.reset()
        long total = 0L
        ByteBuffer buffer = ByteBuffer.wrap(bytes)
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (true) {
                stop.check()
                buffer.clear()
                int count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(bytes, 0, count)
                total = Math.addExact(total, (long) count)
            }
        }
        stop.check()
        return new HashValue(HexFormat.of().formatHex(digest.digest()), total)
    }
}
