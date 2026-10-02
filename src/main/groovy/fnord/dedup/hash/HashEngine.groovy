package fnord.dedup.hash

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.BulkWriter
import fnord.dedup.store.DuckStore
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
        String storedRoot = scan.root as String
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
                        submit(completion, iterator.next(), storedRoot, hasher, stop, options.bufferBytes)
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

    private static void submit(CompletionService<Map> completion, Map candidate, String storedRoot,
                               FileHasher hasher, StopToken stop, int bufferBytes) {
        // A method parameter gives every submitted closure its own candidate binding.
        completion.submit({ -> FileHashTask.calculate(candidate, storedRoot, hasher, stop, bufferBytes) } as Callable<Map>)
    }

}
