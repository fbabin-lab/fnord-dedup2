package fnord.dedup.scan

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.BulkWriter
import fnord.dedup.store.DuckStore
import fnord.dedup.path.StoredPath
import groovy.transform.CompileStatic
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Iterative, disk-backed breadth-first traversal; no whole-tree object graph. */
@CompileStatic
final class DiscoveryEngine {
    static void run(DuckStore store, Map scan, ScanOptions options, StopToken stop, Closure progress) {
        if (scan.get('phase') != 'DISCOVERING') return
        long scanId = ((Number) scan.get('scan_id')).longValue()
        store.recoverDiscovery(scanId)
        Path root = StoredPath.nativeRoot((String) scan.get('root'))
        long nextId = ((Number) scan.get('next_entry_id')).longValue()
        BulkWriter writer = new BulkWriter(store, scanId, 'discovery', nextId, options, progress)
        Long active = null
        try {
            while (!stop.cancelled) {
                List<Map> work = store.pendingDirectories(scanId, options.directoryBatchSize)
                if (work.isEmpty()) break
                for (Map task : work) {
                    if (stop.cancelled) break
                    long directoryId = ((Number) task.get('entry_id')).longValue()
                    active = directoryId
                    String relative = (String) task.get('relative_path')
                    Path directory = StoredPath.resolve(root, relative)
                    try {
                        BasicFileAttributes current = Files.readAttributes(directory, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
                        if (!current.isDirectory()) throw new IOException('Directory disappeared or changed type since discovery')
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                            Iterator<Path> iterator = stream.iterator()
                            while (!stop.cancelled && iterator.hasNext()) {
                                Path child = iterator.next()
                                if (store.excluded(child)) continue
                                String childRelative = StoredPath.storeRelative(root.relativize(child))
                                // Java String paths cannot losslessly represent arbitrary non-UTF-8 bytes.
                                if (!Path.of(child.toString()).equals(child)) {
                                    writer.error('DISCOVERY', childRelative, 'Filename is not representable as UTF-8; entry skipped')
                                    writer.maybeCheckpoint(active)
                                    continue
                                }
                                BasicFileAttributes attributes
                                try {
                                    attributes = Files.readAttributes(child, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
                                } catch (IOException | SecurityException e) {
                                    writer.error('DISCOVERY', childRelative, e.toString())
                                    writer.maybeCheckpoint(active)
                                    continue
                                }
                                String kind = attributes.isRegularFile() ? 'FILE' : attributes.isDirectory() ? 'DIRECTORY' :
                                    attributes.isSymbolicLink() ? 'SYMLINK' : 'OTHER'
                                writer.entry(writer.allocateId(), directoryId, childRelative, child.fileName.toString(), kind, attributes)
                                writer.maybeCheckpoint(active)
                            }
                        }
                    } catch (IOException | DirectoryIteratorException | SecurityException e) {
                        writer.error('DISCOVERY', relative, e.toString())
                    }
                    if (stop.cancelled) break
                    // Failed directories are completed with an explicit error, not silently successful.
                    writer.directoryCompleted(directoryId)
                    active = null
                    writer.maybeCheckpoint(null)
                }
                writer.checkpoint(active)
            }
            writer.checkpoint(active)
        } finally { writer.close() }
        if (!stop.cancelled) {
            long pending = ((Number) store.rows('SELECT count(*) AS n FROM directories WHERE scan_id=? AND NOT completed', scanId)[0].get('n')).longValue()
            if (pending != 0L) throw new IllegalStateException('Directory queue is blocked; database invariants were violated')
            store.phase(scanId, 'READY')
        }
    }
}
