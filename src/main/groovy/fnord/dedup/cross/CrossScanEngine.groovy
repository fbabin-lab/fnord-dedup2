package fnord.dedup.cross

import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.hash.FileHashTask
import fnord.dedup.hash.FileHasher
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Auxiliary cross-scan verification. Only successful hashes persist; scan lifecycle is untouched. */
final class CrossScanEngine {
    private final Dedup engine
    private final CrossScanOptions options
    private final StopToken stop
    private final FileHasher hasher
    private final Map summary = [stage:'cross-scan', phase:'PREPARING', selected_scans:[],
        candidate_sizes:null, candidate_files:null, existing_candidate_hashes:null, hashes_needed:null,
        hashes_attempted:0L, hashes_completed_this_run:0L, hash_failures:0L, unresolved_candidates:null,
        duplicate_groups:null, duplicate_observations:null, duplicate_observations_reported:0L,
        prior_scan_errors:0L, scan_warnings:[], error_samples:[], partial:false, cancelled:false, report_complete:false]

    CrossScanEngine(Dedup engine, CrossScanOptions options, StopToken stop) {
        this.engine = engine
        this.options = options.validate()
        if (stop == null) throw new IllegalArgumentException('StopToken must not be null')
        this.stop = stop
        engine.options.validate()
        hasher = engine.hasher
        if (hasher == null || hasher.algorithm() != 'SHA-256') throw new IllegalArgumentException('Cross-scan verification requires a SHA-256 provider')
    }

    Map run(List<String> names, Closure consumer) {
        if (consumer == null) throw new IllegalArgumentException('A duplicate-row consumer is required')
        summary.selected_scans = names == null ? [] : new ArrayList(names)
        new CrossScanStore(engine.store).withCloseable { CrossScanStore store ->
            try {
                store.select(names, stop)
                for (Map scan : store.selected) {
                    summary.prior_scan_errors += scan.prior_errors as long
                    if ((scan.prior_errors as long) > 0L || scan.phase == 'COMPLETE_WITH_ERRORS') {
                        summary.scan_warnings.add([scan_id:scan.scan_id,scan_name:scan.name,phase:scan.phase,
                            errors:scan.prior_errors,discovery_errors:scan.discovery_errors])
                    }
                }
                summary.partial = !summary.scan_warnings.empty
                summary.putAll(store.prepare(stop))
                summary.unresolved_candidates = summary.hashes_needed
                emit('CANDIDATES_READY')
                stop.check()
                if ((summary.hashes_needed as long) > 0L) hashCandidates(store)
                stop.check()
                summary.partial |= (summary.unresolved_candidates as long) > 0L
                emit('GROUPING')
                summary.putAll(store.groups(stop))
                emit('REPORTING')
                store.eachDuplicate(stop) { Map row ->
                    row.partial = summary.partial
                    consumer.call(row)
                    summary.duplicate_observations_reported++
                }
                stop.check()
                summary.report_complete = true
                summary.phase = summary.partial ? 'COMPLETE_WITH_ERRORS' : 'COMPLETE'
            } catch (CancellationException ignored) {
                summary.cancelled = true
                summary.partial = true
                summary.phase = 'PAUSED'
            }
        }
        emit(summary.phase as String)
        new LinkedHashMap(summary)
    }

    private void emit(String phase) {
        summary.phase = phase
        // Copy scalar counters and collections so caller retention cannot see future changes.
        Map event = new LinkedHashMap(summary)
        event.state = phase
        engine.progress.call(event)
    }

    private void hashCandidates(CrossScanStore store) {
        int workers = engine.options.workers
        int batchSize = engine.options.batchSize
        AtomicInteger number = new AtomicInteger()
        ExecutorService pool = Executors.newFixedThreadPool(workers, { Runnable task ->
            Thread thread = new Thread(task, 'dedup-cross-hash-' + number.incrementAndGet())
            thread.daemon = true
            thread
        } as ThreadFactory)
        CompletionService<Map> completion = new ExecutorCompletionService<>(pool)
        List<Map> batch = []
        long lastCommit = System.nanoTime()
        try {
            emit('HASHING')
            long after = 0L
            while (!stop.cancelled) {
                List<Map> page = store.page(after, batchSize)
                if (page.empty) break
                Iterator<Map> iterator = page.iterator()
                int inFlight = 0
                while (!stop.cancelled && (iterator.hasNext() || inFlight > 0)) {
                    while (!stop.cancelled && iterator.hasNext() && inFlight < workers * 2) {
                        submit(completion, iterator.next())
                        inFlight++
                    }
                    Future<Map> ready = completion.poll(100, TimeUnit.MILLISECONDS)
                    if (ready != null) {
                        Map result
                        try { result = ready.get() } catch (ExecutionException e) { throw e.cause }
                        inFlight--
                        if (!result.cancelled) {
                            summary.hashes_attempted++
                            if (result.error != null) {
                                summary.hash_failures++
                                Map error = [stage:'cross-scan',event:'hash_error',scan_id:result.scan_id,
                                    scan_name:result.scan_name,entry_id:result.entry_id,relative_path:result.relative_path,
                                    path:Path.of(result.scan_root as String).resolve(result.relative_path as String).toString(),
                                    code:result.code,message:(result.error as String).take(4096)]
                                if (summary.error_samples.size() < options.maxErrorSamples) summary.error_samples.add(new LinkedHashMap(error))
                                options.onError.call(error)
                            } else batch.add(result)
                        }
                    }
                    if (batch.size() >= batchSize || System.nanoTime() - lastCommit >= engine.options.commitIntervalMillis * 1_000_000L) {
                        flush(store, batch)
                        lastCommit = System.nanoTime()
                    }
                }
                flush(store, batch)
                lastCommit = System.nanoTime()
                after = page.last().sequence as long
            }
            flush(store, batch)
        } catch (InterruptedException ignored) {
            stop.cancel()
            // Clear the Java interrupt only while finishing collected complete hashes.
            Thread.interrupted()
            try { flush(store, batch) } finally { Thread.currentThread().interrupt() }
        } finally {
            pool.shutdownNow()
            try { pool.awaitTermination(5, TimeUnit.SECONDS) }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        }
    }

    private void flush(CrossScanStore store, List<Map> batch) {
        if (batch.empty) return
        summary.hashes_completed_this_run += store.saveHashes(batch)
        summary.unresolved_candidates = (summary.hashes_needed as long) - (summary.hashes_completed_this_run as long)
        batch.clear()
        emit('HASHES_COMMITTED')
    }

    private void submit(CompletionService<Map> completion, Map candidate) {
        // Separate method binding prevents closure loop-variable capture.
        completion.submit({ ->
            Map result = FileHashTask.calculate(candidate, Path.of(candidate.scan_root as String), hasher, stop, engine.options.bufferBytes)
            result.scan_id = candidate.scan_id
            result.scan_name = candidate.scan_name
            result.scan_root = candidate.scan_root
            result
        } as Callable<Map>)
    }
}
