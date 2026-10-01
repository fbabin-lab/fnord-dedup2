package fnord.dedup.hash

import fnord.dedup.StopToken
import java.nio.file.Path

/**
 * Thread-safe extension point. Implementations must hash the entire regular file,
 * respect cancellation, and return the actual number of bytes read.
 * Version 1 accepts SHA-256 providers only, preventing mixed digest semantics.
 */
interface FileHasher {
    String algorithm()
    HashValue hash(Path path, StopToken stop, int bufferBytes)
}
