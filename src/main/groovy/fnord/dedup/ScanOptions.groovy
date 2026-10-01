package fnord.dedup

import groovy.transform.CompileStatic

/** Performance controls; they may be changed between resume invocations. */
@CompileStatic
class ScanOptions {
    int batchSize = 16_384
    int directoryBatchSize = 128
    int workers = 1
    int bufferBytes = 1_048_576
    long commitIntervalMillis = 2_000L
    int databaseThreads = 2
    String memoryLimit = '1GB'

    ScanOptions validate() {
        require(batchSize >= 1 && batchSize <= 1_000_000, 'batchSize must be 1..1000000')
        require(directoryBatchSize >= 1 && directoryBatchSize <= 10_000, 'directoryBatchSize must be 1..10000')
        require(workers >= 1 && workers <= 64, 'workers must be 1..64')
        require(bufferBytes >= 4_096 && bufferBytes <= 16_777_216, 'bufferBytes must be 4KiB..16MiB')
        require(commitIntervalMillis >= 100 && commitIntervalMillis <= 60_000, 'commitIntervalMillis must be 100..60000')
        require(databaseThreads >= 1 && databaseThreads <= 64, 'databaseThreads must be 1..64')
        require(memoryLimit != null && memoryLimit ==~ /(?i)[1-9][0-9]*(MB|GB)/, 'memoryLimit must be a positive integer followed by MB or GB')
        return this
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message)
    }
}
