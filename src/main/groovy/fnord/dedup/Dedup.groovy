package fnord.dedup

import fnord.dedup.path.NativeFiles
import fnord.dedup.path.StoredPath

import fnord.dedup.hash.FileHasher
import fnord.dedup.cross.CrossScanEngine
import fnord.dedup.cross.CrossScanOptions
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

    Map resume(String name, StopToken stop = new StopToken(), boolean discoverOnly = false, boolean rehash = false, boolean hashComplete = false) {
        discover(name, stop)
        if (!stop.cancelled && !discoverOnly) hash(name, stop, rehash, hashComplete)
        status(name)
    }

    Map discover(String name, StopToken stop = new StopToken()) {
        DiscoveryEngine.run(store, store.scan(name), options.validate(), stop, progress)
        status(name)
    }

    Map hash(String name, StopToken stop = new StopToken(), boolean rehash = false, boolean hashComplete = false) {
        HashEngine.run(store, store.scan(name), options.validate(), stop, progress, hasher, rehash, hashComplete)
        status(name)
    }

    Map status(String name) { store.status(name) }
    List<Map> listScans() { store.rows('SELECT scan_id,name,root,phase,algorithm,created_at,updated_at FROM scans ORDER BY scan_id') }

    void eachDuplicate(String name, boolean allowPartial = false, Closure consumer) { store.duplicateRows(name, allowPartial, consumer) }
    void eachError(String name, Closure consumer) {
        long id = store.scan(name).scan_id as long
        store.eachRow('SELECT phase,relative_path,message,recorded_at_ms FROM scan_errors WHERE scan_id=? ORDER BY recorded_at_ms,relative_path', [id] as Object[], consumer)
    }

    /** Stream cross-scan observations and return coverage; errors use CrossScanOptions.onError. */
    Map crossDuplicates(List<String> names, CrossScanOptions crossOptions = new CrossScanOptions(),
                        StopToken stop = new StopToken(), Closure consumer) {
        new CrossScanEngine(this, crossOptions, stop).run(names, consumer)
    }

    // The archive schema is initialized only when an archive API is called.
    // Ordinary resume intentionally does not opt a scan into archive extraction.
    Map analyzeArchives(String name, ArchiveOptions archiveOptions = new ArchiveOptions(), StopToken stop = new StopToken()) {
        NativeFiles.requireLinux('Archive')
        StoredPath.nativeRoot(store.scan(name).root as String)
        new ArchiveAnalysis(this, archiveOptions).analyze(name, stop)
    }
    private ArchiveStore archives() { new ArchiveStore(store, options.batchSize) }
    Map archiveStatus(String name) { archives().status(name) }
    void eachArchive(String name, Closure consumer) { archives().eachArchive(name, consumer) }
    void eachArchiveEntry(String resultId, Closure consumer) { archives().eachMember(resultId, consumer) }
    void eachArchiveVolume(String resultId, Closure consumer) { archives().eachVolume(resultId, consumer) }
    void eachArchiveError(String name, Closure consumer) { archives().eachError(name, consumer) }

    Map analyzeImages(String name, fnord.dedup.image.ImageOptions imageOptions = new fnord.dedup.image.ImageOptions(), StopToken stop = new StopToken()) {
        NativeFiles.requireLinux('Disk-image')
        StoredPath.nativeRoot(store.scan(name).root as String)
        new fnord.dedup.image.ImageAnalysis(store, options, imageOptions, stop, progress).run(name)
    }
    Map imageStatus(String name) { new fnord.dedup.image.ImageStore(store).status(name) }
    void eachImage(String name, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachImage(name, consumer) }
    void eachImageEntry(String result, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachResult('image_entries', result, consumer) }
    void eachImageFilesystem(String result, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachResult('image_filesystems', result, consumer) }
    void eachImagePartition(String result, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachResult('image_partitions', result, consumer) }
    void eachImageComponent(String result, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachResult('image_components', result, consumer) }
    void eachImageError(String name, Closure consumer) { new fnord.dedup.image.ImageStore(store).eachError(name, consumer) }

    @Override void close() { store.close() }
}
