package fnord.dedup.hash

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.BulkWriter
import fnord.dedup.store.DuckStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Size candidates are materialized once in DuckDB, then consumed in bounded pages. */
final class HashEngine {
    static void run(DuckStore store, Map scan, ScanOptions options, StopToken stop,
                    Closure progress, FileHasher hasher, boolean rehash) {
        if (scan.phase == 'DISCOVERING') throw new IllegalStateException('Discovery must finish before hashing; use resume')
        if (hasher.algorithm() != 'SHA-256') throw new IllegalArgumentException('This schema accepts SHA-256 providers only')
        if (stop.cancelled) return
        long scanId = scan.scan_id as long
        Path root = Path.of(scan.root as String)
        store.prepareCandidates(scanId, rehash)
        AtomicInteger threadNumber = new AtomicInteger()
        ExecutorService pool = Executors.newFixedThreadPool(options.workers, { Runnable runnable ->
            Thread thread = new Thread(runnable, 'dedup-hash-' + threadNumber.incrementAndGet())
            thread.daemon = true
            thread
        } as ThreadFactory)
        CompletionService<Map> completion = new ExecutorCompletionService<>(pool)
        BulkWriter writer = new BulkWriter(store, scanId, 'hashing', 0L, options, progress)
        boolean exhausted = false
        try {
            long after = 0L
            while (!stop.cancelled) {
                List<Map> page = store.candidates(after, options.batchSize)
                if (page.empty) { exhausted = true; break }
                Iterator<Map> iterator = page.iterator()
                int inFlight = 0
                while (!stop.cancelled && (iterator.hasNext() || inFlight > 0)) {
                    while (!stop.cancelled && iterator.hasNext() && inFlight < options.workers * 2) {
                        submit(completion, iterator.next(), root, hasher, stop, options.bufferBytes)
                        inFlight++
                    }
                    Future<Map> ready = completion.poll(100, TimeUnit.MILLISECONDS)
                    if (ready != null) {
                        Map result
                        try { result = ready.get() }
                        catch (ExecutionException e) { throw e.cause }
                        inFlight--
                        if (!result.cancelled) {
                            if (result.error != null) writer.error('HASHING', result.relative_path as String, result.error as String)
                            else writer.hash(result.entry_id as long, result.sha256 as String)
                        }
                    }
                    writer.maybeCheckpoint(null)
                }
                writer.checkpoint(null)
                after = page.last().sequence as long
            }
            writer.checkpoint(null)
        } catch (InterruptedException ignored) {
            stop.cancel()
            Thread.currentThread().interrupt()
        } finally {
            pool.shutdownNow()
            try { pool.awaitTermination(5, TimeUnit.SECONDS) }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
            writer.close()
        }
        if (!stop.cancelled && exhausted) {
            store.phase(scanId, store.hasErrors(scanId) ? 'COMPLETE_WITH_ERRORS' : 'COMPLETE')
        }
    }

    private static void submit(CompletionService<Map> completion, Map candidate, Path root,
                               FileHasher hasher, StopToken stop, int bufferBytes) {
        // A method parameter gives every submitted closure its own candidate binding.
        completion.submit({ -> calculate(candidate, root, hasher, stop, bufferBytes) } as Callable<Map>)
    }

    private static Map calculate(Map candidate, Path root, FileHasher hasher, StopToken stop, int bufferBytes) {
        Map result = [entry_id: candidate.entry_id, relative_path: candidate.relative_path]
        try {
            stop.check()
            Path path = root.resolve(candidate.relative_path as String)
            verify(path, candidate)
            HashValue value = hasher.hash(path, stop, bufferBytes)
            stop.check()
            verify(path, candidate)
            if (value == null || value.bytesRead != (candidate.size as long)) {
                throw new IOException('File length changed while hashing')
            }
            if (!(value.hex ==~ /[0-9a-f]{64}/)) throw new IllegalArgumentException('Hasher returned an invalid SHA-256 digest')
            result.sha256 = value.hex
        } catch (CancellationException ignored) {
            result.cancelled = true
        } catch (IOException | SecurityException e) {
            if (stop.cancelled) result.cancelled = true
            else result.error = e.toString()
        }
        result
    }

    private static void verify(Path path, Map candidate) {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
        def modified = attributes.lastModifiedTime().toInstant()
        if (!attributes.isRegularFile() || attributes.size() != (candidate.size as long) ||
            modified.epochSecond != (candidate.modified_sec as long) || modified.nano != (candidate.modified_nano as int)) {
            throw new IOException('File changed since discovery; excluded from duplicate results. Create a new scan to refresh metadata.')
        }
    }
}
