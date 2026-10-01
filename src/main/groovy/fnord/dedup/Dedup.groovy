package fnord.dedup

import fnord.dedup.hash.FileHasher
import fnord.dedup.hash.HashEngine
import fnord.dedup.hash.Sha256Hasher
import fnord.dedup.scan.DiscoveryEngine
import fnord.dedup.store.DuckStore
import java.nio.file.Path

/**
 * Public, synchronous scripting API. One owner thread per instance/database.
 * The source tree is never modified. Use StopToken for cooperative cancellation.
 */
final class Dedup implements AutoCloseable {
    final ScanOptions options
    final DuckStore store
    Closure progress = { Map ignored -> }
    FileHasher hasher = new Sha256Hasher()

    private Dedup(Path database, ScanOptions options) {
        this.options = options.validate()
        store = new DuckStore(database, options)
    }

    static Dedup open(Path database, ScanOptions options = new ScanOptions()) {
        new Dedup(database, options)
    }

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

    List<Map> listScans() {
        store.rows('SELECT scan_id,name,root,phase,algorithm,created_at,updated_at FROM scans ORDER BY scan_id')
    }

    void eachDuplicate(String name, boolean allowPartial = false, Closure consumer) {
        store.duplicateRows(name, allowPartial, consumer)
    }

    void eachError(String name, Closure consumer) {
        long id = store.scan(name).scan_id as long
        store.eachRow('SELECT phase,relative_path,message,recorded_at_ms FROM scan_errors WHERE scan_id=? ORDER BY recorded_at_ms,relative_path', [id] as Object[], consumer)
    }

    @Override void close() { store.close() }
}
