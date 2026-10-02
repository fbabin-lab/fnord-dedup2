package fnord.dedup.archive

import java.nio.file.Path

/** Per-invocation controls. Resource-limited results are never reusable caches. */
class ArchiveOptions {
    Path tempDirectory
    String python = '/usr/bin/python3'
    int maxDepth = 32
    long maxMembers = 1_000_000L
    long maxExpandedBytes = 100L * 1024 * 1024 * 1024
    long maxTempBytes = 100L * 1024 * 1024 * 1024
    long minFreeBytes = 1024L * 1024 * 1024
    long nativeMemoryBytes = 2L * 1024 * 1024 * 1024
    long timeoutSeconds = 3600L
    int maxVolumes = 10000
    boolean retryErrors = false
    boolean force = false

    ArchiveOptions validate() {
        if (maxDepth < 0 || maxDepth > 128) throw new IllegalArgumentException('Archive depth must be 0..128; top-level depth is zero')
        if (maxMembers < 1 || maxExpandedBytes < 1 || maxTempBytes < 1 || minFreeBytes < 0 ||
            nativeMemoryBytes < 128L * 1024 * 1024 || timeoutSeconds < 1 || timeoutSeconds > 604800 ||
            maxVolumes < 1 || maxVolumes > 100000) throw new IllegalArgumentException('Invalid archive resource limits')
        if (!Path.of(python).isAbsolute()) throw new IllegalArgumentException('Python executable must be an absolute path')
        this
    }

    Map policy() {
        [protocol: 1, maxDepth: maxDepth, maxMembers: maxMembers, maxExpandedBytes: maxExpandedBytes,
         maxTempBytes: maxTempBytes, nativeMemoryBytes: nativeMemoryBytes, maxVolumes: maxVolumes]
    }
}
