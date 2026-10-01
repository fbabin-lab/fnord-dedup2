package fnord.dedup

import fnord.dedup.store.BulkWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Regression: JDBC's lazy BEGIN must occur before appender flush, not after it. */
class BulkWriterTest {
    @TempDir Path work

    @Test void firstAppenderTransactionRollsBackBothRowsAndCheckpoint() {
        exercise(false)
    }

    @Test void transactionAfterCheckpointRollsBackOnlyNewRows() {
        exercise(true)
    }

    private void exercise(boolean commitFirst) {
        Path root = Files.createDirectory(work.resolve('input'))
        Files.writeString(root.resolve('a'), 'same')
        Files.writeString(root.resolve('b'), 'same')
        ScanOptions options = new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')
        Dedup.open(work.resolve('scan.duckdb'), options).withCloseable { Dedup d ->
            Map scan = d.createScan('rollback', root)
            long id = scan.scan_id as long
            BulkWriter writer = new BulkWriter(d.store, id, 'discovery', 2L, options, { Map ignored -> })
            try {
                if (commitFirst) {
                    writer.entry(writer.allocateId(), 1L, 'a', 'a', 'FILE', Files.readAttributes(root.resolve('a'), BasicFileAttributes))
                    writer.checkpoint(1L)
                }
                String name = commitFirst ? 'b' : 'a'
                writer.entry(writer.allocateId(), 1L, name, name, 'FILE', Files.readAttributes(root.resolve(name), BasicFileAttributes))
                // Flush BEFORE executing any SQL that could accidentally activate
                // a transaction and mask the appender integration regression.
                writer.flushPendingRows()
                d.store.exec('UPDATE scans SET active_dir=1,next_entry_id=? WHERE scan_id=?', writer.nextEntryId, id)
            } finally { writer.close() }
            assert d.status('rollback').files == (commitFirst ? 1 : 0)
            assert d.store.scan('rollback').next_entry_id == (commitFirst ? 3 : 2)
            assert d.store.scan('rollback').active_dir == (commitFirst ? 1 : null)
            Map completed = d.resume('rollback')
            assert completed.phase == 'COMPLETE'
            assert completed.files == 2
            assert completed.hashes_completed == 2
            assert d.store.rows('SELECT relative_path,count(*) AS n FROM entries GROUP BY relative_path HAVING count(*)>1').empty
        }
    }
}
