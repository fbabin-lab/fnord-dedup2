package fnord.dedup.store

import fnord.dedup.ScanOptions
import groovy.transform.CompileStatic
import org.duckdb.DuckDBAppender
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * One transaction spans appender rows and their discovery checkpoint.
 * batchSize is a transaction/recovery bound, not DuckDB's internal vector size.
 */
@CompileStatic
final class BulkWriter implements AutoCloseable {
    private final DuckStore store
    private final long scanId
    private final String stage
    private final ScanOptions options
    private final Closure progress
    private final Map<String, DuckDBAppender> appenders = new LinkedHashMap<>()
    private final List<Long> completedDirectories = new ArrayList<>()
    long nextEntryId
    private long rowsInTransaction = 0L
    private long entriesWritten = 0L
    private long hashesWritten = 0L
    private long errorsWritten = 0L
    private long lastCommitNanos = System.nanoTime()
    private boolean dirty = false

    BulkWriter(DuckStore store, long scanId, String stage, long nextEntryId,
               ScanOptions options, Closure progress) {
        this.store = store
        this.scanId = scanId
        this.stage = stage
        this.nextEntryId = nextEntryId
        this.options = options
        this.progress = progress
        if (!store.connection.autoCommit) throw new IllegalStateException('Writer requires a free connection')
        store.connection.autoCommit = false
    }

    private DuckDBAppender appender(String table) {
        DuckDBAppender value = appenders.get(table)
        if (value == null) {
            value = store.connection.createAppender('main', table)
            appenders.put(table, value)
        }
        return value
    }

    long allocateId() {
        long id = nextEntryId
        nextEntryId = Math.addExact(nextEntryId, 1L)
        return id
    }

    void entry(long id, long parent, String relative, String filename, String kind, BasicFileAttributes attributes) {
        Instant modified = attributes.lastModifiedTime().toInstant()
        DuckDBAppender a = appender('entries')
        a.beginRow()
        a.append(scanId); a.append(id); a.append(parent)
        a.append(relative); a.append(filename); a.append(kind)
        a.append(attributes.size()); a.append(modified.epochSecond); a.append(modified.nano)
        a.endRow()
        entriesWritten++
        rowsInTransaction++
        dirty = true
        if (kind == 'DIRECTORY') {
            DuckDBAppender d = appender('directories')
            d.beginRow()
            d.append(scanId); d.append(id); d.append(parent); d.append(relative); d.append(false)
            d.endRow()
        }
    }

    void directoryCompleted(long id) {
        completedDirectories.add(id)
        dirty = true
    }

    void hash(long entryId, String hex) {
        DuckDBAppender a = appender('hashes')
        a.beginRow(); a.append(scanId); a.append(entryId); a.append(hex); a.endRow()
        hashesWritten++
        rowsInTransaction++
        dirty = true
    }

    void error(String phase, String relative, String message) {
        DuckDBAppender a = appender('scan_errors')
        String limited = message == null ? 'Unknown error' : message.take(4096)
        a.beginRow()
        a.append(scanId); a.append(phase); a.append(relative); a.append(limited); a.append(System.currentTimeMillis())
        a.endRow()
        errorsWritten++
        rowsInTransaction++
        dirty = true
    }

    void maybeCheckpoint(Long activeDirectory) {
        if (rowsInTransaction >= options.batchSize || completedDirectories.size() >= options.directoryBatchSize ||
            System.nanoTime() - lastCommitNanos >= options.commitIntervalMillis * 1_000_000L) {
            checkpoint(activeDirectory)
        }
    }

    void checkpoint(Long activeDirectory) {
        if (!dirty) return
        closeAppenders()
        if (stage == 'discovery') {
            if (!completedDirectories.isEmpty()) {
                // Values are internal long IDs, never SQL from a user or filename.
                String ids = completedDirectories.join(',')
                store.exec("UPDATE directories SET completed=true WHERE scan_id=? AND entry_id IN (${ids})".toString(), scanId)
            }
            store.exec('UPDATE scans SET active_dir=?,next_entry_id=?,updated_at=current_timestamp WHERE scan_id=?', activeDirectory, nextEntryId, scanId)
        } else {
            store.exec('UPDATE scans SET updated_at=current_timestamp WHERE scan_id=?', scanId)
        }
        store.connection.commit()
        completedDirectories.clear()
        rowsInTransaction = 0L
        dirty = false
        lastCommitNanos = System.nanoTime()
        progress.call([stage: stage, entries_written_this_run: entriesWritten,
                       hashes_written_this_run: hashesWritten, errors_this_run: errorsWritten])
    }

    private void closeAppenders() {
        Exception failure = null
        for (DuckDBAppender a : appenders.values()) {
            try { a.close() } catch (Exception e) {
                if (failure == null) failure = e
                else failure.addSuppressed(e)
            }
        }
        appenders.clear()
        if (failure != null) throw failure
    }

    /** close is rollback-only; callers explicitly checkpoint successful work. */
    @Override void close() {
        try {
            try { closeAppenders() } finally { store.connection.rollback() }
        } finally { store.connection.autoCommit = true }
    }
}
