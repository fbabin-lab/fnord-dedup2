package fnord.dedup

import fnord.dedup.hash.FileHasher
import fnord.dedup.hash.HashEngine
import fnord.dedup.hash.Sha256Hasher
import fnord.dedup.scan.DiscoveryEngine
import fnord.dedup.store.DuckStore
import fnord.dedup.archive.ArchiveAnalysis
import fnord.dedup.archive.ArchiveOptions
import fnord.dedup.archive.ArchiveStore
import java.nio.file.Path

/** Public synchronous API. One owner thread; only StopToken is cross-thread. */
final class Dedup implements AutoCloseable {
    final ScanOptions options
    final DuckStore store
    Closure progress = { Map ignored -> }
    FileHasher hasher = new Sha256Hasher()

    private Dedup(Path database, ScanOptions options) {
        this.options = options.validate()
        store = new DuckStore(database, options)
    }

    static Dedup open(Path database, ScanOptions options = new ScanOptions()) { new Dedup(database, options) }
    Map createScan(String name, Path root) { store.createScan(name, root) }

    Map scan(String name, Path root, StopToken stop = new StopToken(), boolean discoverOnly = false) {
        createScan(name, root)
        resume(name, stop, discoverOnly)
    }

    Map resume(String name, StopToken stop = new StopToken(), boolean discoverOnly = false, boolean rehash = false) {
        discover(name, stop)
        if (!stop.cancelled && !discoverOnly) hash(name, stop, rehash)
        status(name)
    }

    Map discover(String name, StopToken stop = new StopToken()) {
        DiscoveryEngine.run(store, store.scan(name), options.validate(), stop, progress)
        status(name)
    }

    Map hash(String name, StopToken stop = new StopToken(), boolean rehash = false) {
        HashEngine.run(store, store.scan(name), options.validate(), stop, progress, hasher, rehash)
        status(name)
    }

    Map status(String name) { store.status(name) }
    List<Map> listScans() { store.rows('SELECT scan_id,name,root,phase,algorithm,created_at,updated_at FROM scans ORDER BY scan_id') }

    void eachDuplicate(String name, boolean allowPartial = false, Closure consumer) { store.duplicateRows(name, allowPartial, consumer) }
    void eachError(String name, Closure consumer) {
        long id = store.scan(name).scan_id as long
        store.eachRow('SELECT phase,relative_path,message,recorded_at_ms FROM scan_errors WHERE scan_id=? ORDER BY recorded_at_ms,relative_path', [id] as Object[], consumer)
    }

    // The archive schema is initialized only when an archive API is called.
    // Ordinary resume intentionally does not opt a scan into archive extraction.
    Map analyzeArchives(String name, ArchiveOptions archiveOptions = new ArchiveOptions(), StopToken stop = new StopToken()) {
        new ArchiveAnalysis(this, archiveOptions).analyze(name, stop)
    }
    private ArchiveStore archives() { new ArchiveStore(store, options.batchSize) }
    Map archiveStatus(String name) { archives().status(name) }
    void eachArchive(String name, Closure consumer) { archives().eachArchive(name, consumer) }
    void eachArchiveEntry(String resultId, Closure consumer) { archives().eachMember(resultId, consumer) }
    void eachArchiveVolume(String resultId, Closure consumer) { archives().eachVolume(resultId, consumer) }
    void eachArchiveError(String name, Closure consumer) { archives().eachError(name, consumer) }

    @Override void close() { store.close() }
}
