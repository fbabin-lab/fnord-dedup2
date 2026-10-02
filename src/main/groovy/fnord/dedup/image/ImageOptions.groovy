package fnord.dedup.image

import java.nio.file.Path

/** Limits are explicit; guest file bytes are streamed, not charged as temporary copies. */
class ImageOptions {
    Path tempDirectory
    String python = '/usr/bin/python3'
    String containerHash = 'candidate'
    String acceleration = 'auto'
    long maxFiles = 5_000_000L
    long maxTempBytes = 1_099_511_627_776L
    long minFreeBytes = 5_368_709_120L
    long maxListingBytes = 268_435_456L
    long timeoutSeconds = 14_400L
    long nativeMemoryBytes = 4_294_967_296L
    int applianceMemoryMiB = 768
    int maxComponents = 256
    boolean retryErrors = false
    boolean force = false

    ImageOptions validate() {
        if (!Path.of(python).isAbsolute()) throw new IllegalArgumentException('Image Python path must be absolute')
        if (!(acceleration in ['auto','tcg','kvm'])) throw new IllegalArgumentException('Image acceleration must be auto, tcg or kvm')
        if (!(containerHash in ['candidate','always','never'])) throw new IllegalArgumentException('Image container hash must be candidate, always or never')
        if (maxFiles < 1 || maxTempBytes < 1 || minFreeBytes < 0 || maxListingBytes < 4096 || maxListingBytes > maxTempBytes ||
            timeoutSeconds < 1 || timeoutSeconds > 604800 || nativeMemoryBytes < 1073741824L ||
            applianceMemoryMiB < 256 || applianceMemoryMiB > 16384 || nativeMemoryBytes < applianceMemoryMiB * 1048576L + 268435456L ||
            maxComponents < 1 || maxComponents > 1024) throw new IllegalArgumentException('Invalid image resource limits')
        this
    }

    Map nativeSettings() {
        [acceleration:acceleration, max_files:maxFiles, max_temp_bytes:maxTempBytes, min_free_bytes:minFreeBytes,
         max_listing_bytes:maxListingBytes, timeout:timeoutSeconds, memory_bytes:nativeMemoryBytes,
         appliance_memory_mib:applianceMemoryMiB]
    }
}
