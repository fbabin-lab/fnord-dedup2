package fnord.dedup

import fnord.dedup.hash.FileHasher
import fnord.dedup.hash.HashValue
import fnord.dedup.hash.Sha256Hasher
import fnord.dedup.path.StoredPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import static org.junit.jupiter.api.Assertions.assertThrows

class DedupTest {
    @TempDir Path work

    Path root(String name = 'input') { Files.createDirectories(work.resolve(name)) }
    Path db() { work.resolve('scans.duckdb') }
    ScanOptions options(int batch = 16) {
        new ScanOptions(batchSize: batch, directoryBatchSize: 4, databaseThreads: 1, memoryLimit: '128MB')
    }
    static Path file(Path root, String relative, String content = 'same') {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }
    static List<Map> duplicates(Dedup d, String name, boolean partial = false) {
        List<Map> result = []
        d.eachDuplicate(name, partial) { Map row -> result.add(row) }
        result
    }

    @Test void findsDuplicatesOnlyAfterSameSizeAndSameHash() {
        Path input = root()
        file(input, 'alpha/a.txt')
        file(input, 'beta/b.bin')
        file(input, 'beta/not-a-duplicate', 'else')
        file(input, 'unique', 'a-unique-file-length')
        file(input, 'empty-a', '')
        file(input, 'empty-b', '')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            Map status = d.scan('first', input)
            assert status.phase == 'COMPLETE'
            assert status.files == 6
            assert status.directories == 3
            assert status.hashes_completed == 5
            assert status.candidate_files == 5
            List<Map> rows = duplicates(d, 'first')
            assert rows.size() == 4
            assert rows*.relative_path.toSet() == ['alpha/a.txt', 'beta/b.bin', 'empty-a', 'empty-b'].toSet()
            assert rows.every { it.copies == 2 && !it.partial }
            assert rows.findAll { it.size == 0 }*.sha256.unique() == ['e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855']
        }
    }

    @Test void storesNanosecondMetadataWithoutInodesOrPermissions() {
        Path input = root()
        Path path = file(input, 'a')
        Files.setLastModifiedTime(path, FileTime.from(Instant.parse('2024-01-02T03:04:05.123456789Z')))
        Instant actual = Files.getLastModifiedTime(path).toInstant()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('metadata', input, new StopToken(), true)
            Map row = d.store.rows("SELECT * FROM entries WHERE kind='FILE'")[0]
            assert row.filename == 'a'
            assert row.relative_path == 'a'
            assert row.size == 4L
            assert row.modified_sec == actual.epochSecond
            assert row.modified_nano == actual.nano
            assert row.keySet() == ['scan_id','entry_id','parent_id','relative_path','filename','kind','size','modified_sec','modified_nano'].toSet()
            assert d.status('metadata').hashes_completed == 0
            assert d.status('metadata').phase == 'READY'
        }
    }

    @Test void emptyDirectoryCompletesWithoutHashes() {
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            Map status = d.scan('empty', root())
            assert status.phase == 'COMPLETE'
            assert status.files == 0
            assert status.directories == 1
            assert status.hashes_completed == 0
            assert duplicates(d, 'empty').empty
        }
    }

    @Test void namesAreUniqueAndRootsAreValidated() {
        Path input = root()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.createScan('named', input)
            assertThrows(IllegalArgumentException) { d.createScan('named', input) }
            assertThrows(IllegalArgumentException) { d.createScan('  ', input) }
            assertThrows(IllegalArgumentException) { d.createScan('file-root', file(input, 'a')) }
            assertThrows(IllegalArgumentException) { d.status('missing') }
            assert d.listScans()*.name == ['named']
        }
    }

    @Test void scansAreIsolatedEvenWhenContentMatchesAcrossScans() {
        Path a = root('a'); Path b = root('b')
        file(a, 'first'); file(b, 'second')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('a', a); d.scan('b', b)
            assert d.listScans()*.name == ['a', 'b']
            assert d.status('a').hashes_completed == 0
            assert d.status('b').hashes_completed == 0
            assert duplicates(d, 'a').empty && duplicates(d, 'b').empty
        }
    }

    @Test void discoveryDoesNotReadContentsAndPrematureReportsAreExplicit() {
        Path input = root(); file(input, 'a'); file(input, 'b')
        CountingHasher counter = new CountingHasher()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.hasher = counter
            d.createScan('two-pass', input)
            assertThrows(IllegalStateException) { d.hash('two-pass') }
            d.discover('two-pass')
            assert counter.paths.empty
            assertThrows(IllegalStateException) { duplicates(d, 'two-pass') }
            assert duplicates(d, 'two-pass', true).empty
            d.hash('two-pass')
            assert counter.paths.size() == 2
            assert duplicates(d, 'two-pass').size() == 2
        }
    }

    @Test void resumesAnInterruptedWideDirectoryWithoutDuplicateInventoryRows() {
        Path input = root()
        (1..31).each { file(input, "file-${it}") }
        file(input, 'child/nested')
        StopToken stop = new StopToken()
        Dedup.open(db(), options(3)).withCloseable { Dedup d ->
            d.progress = { Map event -> if (event.stage == 'discovery') stop.cancel() }
            d.scan('paused', input, stop)
            assert d.status('paused').phase == 'DISCOVERING'
            assert d.status('paused').hashes_completed == 0
            assert d.store.scan('paused').active_dir != null
        }
        Dedup.open(db(), options(5)).withCloseable { Dedup d ->
            Map status = d.resume('paused')
            assert status.phase == 'COMPLETE'
            assert status.files == 32
            assert status.directories == 2
            assert status.hashes_completed == 32
            assert d.store.rows('SELECT relative_path,count(*) AS n FROM entries GROUP BY relative_path HAVING count(*)>1').empty
            assert duplicates(d, 'paused').size() == 32
        }
    }

    @Test void repeatedInterruptionsPreserveCompletedDirectoriesAndQueueInvariants() {
        Path input = root()
        (1..6).each { int directory -> (1..7).each { file(input, "d${directory}/f${it}") } }
        Dedup.open(db(), options(4)).withCloseable { it.createScan('repeat', input) }
        3.times {
            StopToken stop = new StopToken()
            Dedup.open(db(), options(4)).withCloseable { Dedup d ->
                int checkpoints = 0
                d.progress = { Map event -> if (event.stage == 'discovery' && ++checkpoints == 3) stop.cancel() }
                d.resume('repeat', stop, true)
            }
        }
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            Map status = d.resume('repeat')
            assert status.files == 42
            assert status.directories == 7
            assert status.pending_directories == 0
            assert d.store.rows('SELECT entry_id,count(*) AS n FROM entries GROUP BY entry_id HAVING count(*)>1').empty
            assert d.store.rows('SELECT relative_path,count(*) AS n FROM entries GROUP BY relative_path HAVING count(*)>1').empty
        }
    }

    @Test void resumesHashingUsingOnlyCompletedChecksums() {
        Path input = root(); (1..10).each { file(input, "f${it}") }
        StopToken stop = new StopToken()
        Dedup.open(db(), options(1)).withCloseable { Dedup d ->
            d.scan('hash-pause', input, new StopToken(), true)
            d.progress = { Map event -> if (event.stage == 'hashing') stop.cancel() }
            d.hash('hash-pause', stop)
            assert d.status('hash-pause').phase == 'HASHING'
            assert d.status('hash-pause').hashes_completed == 1
        }
        CountingHasher counter = new CountingHasher()
        Dedup.open(db(), options(3)).withCloseable { Dedup d ->
            d.hasher = counter
            Map status = d.resume('hash-pause')
            assert status.phase == 'COMPLETE'
            assert status.hashes_completed == 10
            assert counter.paths.size() == 9
            assert duplicates(d, 'hash-pause').size() == 10
        }
    }

    @Test void rehashRecomputesOnlyExistingHashesAndKeepsOldHashOnFailure() {
        Path input = root()
        file(input, 'pair-a', 'same')
        file(input, 'pair-b', 'same')
        file(input, 'unique', 'unique-length')
        CountingHasher counter = new CountingHasher()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.hasher = counter
            d.scan('redo', input)
            assert counter.paths*.fileName*.toString().toSet() == ['pair-a','pair-b'].toSet()
            assert d.status('redo').hashes_completed == 2
            String before = d.store.rows("SELECT sha256 FROM hashes h JOIN entries e USING(scan_id,entry_id) WHERE e.filename='pair-a'")[0].sha256
            counter.paths.clear()
            d.hash('redo', new StopToken(), true, false)
            assert counter.paths*.fileName*.toString().toSet() == ['pair-a','pair-b'].toSet()
            assert d.status('redo').hashes_completed == 2
            assert d.store.rows('SELECT count(*) AS n FROM hashes')[0].n == 2
            d.hasher = new SelectiveFailingHasher('pair-a')
            d.hash('redo', new StopToken(), true, false)
            assert d.store.rows("SELECT sha256 FROM hashes h JOIN entries e USING(scan_id,entry_id) WHERE e.filename='pair-a'")[0].sha256 == before
            assert d.status('redo').hashes_completed == 2
        }
    }

    @Test void hashCompleteHashesEveryMissingRegularFileWithoutRehashingExistingOnes() {
        Path input = root()
        file(input, 'pair-a', 'same')
        file(input, 'pair-b', 'same')
        file(input, 'unique-a', 'unique-A')
        file(input, 'unique-b', 'another unique value')
        CountingHasher counter = new CountingHasher()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.hasher = counter
            d.scan('complete', input)
            assert d.status('complete').hashes_completed == 2
            counter.paths.clear()
            d.hash('complete', new StopToken(), false, true)
            assert counter.paths*.fileName*.toString().toSet() == ['unique-a','unique-b'].toSet()
            assert d.status('complete').hashes_completed == 4
            counter.paths.clear()
            d.hash('complete', new StopToken(), false, true)
            assert counter.paths.empty
            assert d.status('complete').hashes_completed == 4
        }
    }

    @Test void rehashAndHashCompleteTogetherProcessEveryRegularFile() {
        Path input = root()
        file(input, 'pair-a', 'same')
        file(input, 'pair-b', 'same')
        file(input, 'unique', 'unique value')
        CountingHasher counter = new CountingHasher()
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.hasher = counter
            d.scan('all', input)
            assert d.status('all').hashes_completed == 2
            counter.paths.clear()
            d.hash('all', new StopToken(), true, true)
            assert counter.paths*.fileName*.toString().toSet() == ['pair-a','pair-b','unique'].toSet()
            assert d.status('all').hashes_completed == 3
            assert d.store.rows('SELECT entry_id,count(*) AS n FROM hashes GROUP BY entry_id HAVING count(*)>1').empty
        }
    }

    @Test void detectsSameSizeModificationBeforeHashing() {
        Path input = root(); Path a = file(input, 'a'); file(input, 'b')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('changed', input, new StopToken(), true)
            Instant old = Files.getLastModifiedTime(a).toInstant()
            Files.writeString(a, 'diff')
            Files.setLastModifiedTime(a, FileTime.from(old.plusSeconds(1)))
            Map status = d.hash('changed')
            assert status.phase == 'COMPLETE_WITH_ERRORS'
            assert status.errors == 1
            assert status.hashes_completed == 1
            assert duplicates(d, 'changed').empty
            List<Map> errors = []
            d.eachError('changed') { errors.add(it) }
            assert errors[0].relative_path == 'a'
            assert errors[0].message.contains('changed since discovery')
        }
    }

    @Test void detectsMutationDuringHashing() {
        Path input = root(); file(input, 'a'); file(input, 'b')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('during', input, new StopToken(), true)
            d.hasher = new MutatingHasher()
            Map status = d.hash('during')
            assert status.errors == 1
            assert status.hashes_completed == 1
            assert duplicates(d, 'during').empty
        }
    }

    @Test void failedHashesCanBeRetriedWithoutAStatusTable() {
        Path input = root(); file(input, 'a'); file(input, 'b')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('retry', input, new StopToken(), true)
            d.hasher = new FailingHasher()
            assert d.hash('retry').errors == 2
            assert d.status('retry').hashes_completed == 0
            d.hasher = new Sha256Hasher()
            assert d.resume('retry').phase == 'COMPLETE'
            assert d.status('retry').errors == 0
            assert duplicates(d, 'retry').size() == 2
        }
    }

    @Test void missingFilesDoNotBecomeDuplicates() {
        Path input = root(); Path a = file(input, 'a'); file(input, 'b')
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            d.scan('missing-file', input, new StopToken(), true)
            Files.delete(a)
            Map status = d.hash('missing-file')
            assert status.phase == 'COMPLETE_WITH_ERRORS'
            assert status.hashes_completed == 1
            assert status.errors == 1
            assert duplicates(d, 'missing-file').empty
        }
    }

    @Test void doesNotFollowSymlinksOrHashFifos() {
        Assumptions.assumeFalse(StoredPath.windowsHost(), 'POSIX special-file fixture')
        Path input = root(); Path outside = root('outside'); file(outside, 'secret')
        file(input, 'regular')
        Files.createSymbolicLink(input.resolve('loop'), input)
        Files.createSymbolicLink(input.resolve('outside-link'), outside.resolve('secret'))
        Process mkfifo = new ProcessBuilder('mkfifo', input.resolve('pipe').toString()).start()
        assert mkfifo.waitFor(5, TimeUnit.SECONDS)
        assert mkfifo.exitValue() == 0
        Dedup.open(db(), options()).withCloseable { Dedup d ->
            Map status = d.scan('links', input)
            assert status.files == 1
            assert status.directories == 1
            assert status.symlinks == 2
            assert status.other_entries == 1
            assert status.hashes_completed == 0
        }
    }

    @Test void handlesUnicodeNewlinesTabsQuotesAndLeadingDashesInNames() {
        Path input = root()
        List<String> names = StoredPath.windowsHost() ? ['space name', 'été-東京', "single'quote", '-option'] : ['space name', 'été-東京', "line\nbreak", "tab\tname", "single'quote", 'double"quote', '-option', 'back\\slash']
        names.each { file(input, it) }
        Dedup.open(work.resolve("db ' quoted.duckdb"), options()).withCloseable { Dedup d ->
            d.scan("scan's name", input)
            assert duplicates(d, "scan's name")*.filename.toSet() == names.toSet()
        }
    }

    @Test void excludesTheDatabaseAndAllOfItsSidecarsInsideTheRoot() {
        Path input = root(); file(input, 'a'); file(input, 'b')
        Dedup.open(input.resolve('catalog.duckdb'), options()).withCloseable { Dedup d ->
            Files.createDirectories(d.store.tempDirectory)
            Files.writeString(d.store.tempDirectory.resolve('not-source-data'), 'same')
            Map status = d.scan('self', input)
            assert status.files == 2
            assert status.directories == 1
            assert duplicates(d, 'self').size() == 2
        }
    }

    @Test void databaseLockFailsFastAndCanBeReacquiredAfterClose() {
        Dedup first = Dedup.open(db(), options())
        try { assertThrows(IllegalStateException) { Dedup.open(db(), options()) } }
        finally { first.close() }
        Dedup.open(db(), options()).withCloseable { assert it.listScans().empty }
        assert Files.exists(Path.of(db().toString() + '.lock'))
    }

    @Test void refusesUnrelatedDatabasesWithoutChangingThem() {
        DriverManager.getConnection('jdbc:duckdb:' + db()).withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute('CREATE TABLE unrelated (id INTEGER)') }
        }
        assertThrows(IllegalArgumentException) { Dedup.open(db(), options()) }
        DriverManager.getConnection('jdbc:duckdb:' + db()).withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('SELECT count(*) FROM unrelated').withCloseable { assert it.next() }
            }
        }
    }

    @Test void validatesPerformanceLimits() {
        assertThrows(IllegalArgumentException) { new ScanOptions(batchSize: 0).validate() }
        assertThrows(IllegalArgumentException) { new ScanOptions(workers: 65).validate() }
        assertThrows(IllegalArgumentException) { new ScanOptions(memoryLimit: "1GB'; DROP TABLE scans;--").validate() }
        assertThrows(IllegalArgumentException) { new ScanOptions(bufferBytes: 1).validate() }
        assert new ScanOptions(memoryLimit: '512mb', workers: 4).validate()
    }

    @Test void parallelWorkersProduceExactlyOneHashPerCandidate() {
        Path input = root(); (1..73).each { file(input, "f${it}", 'same-content') }
        ScanOptions opts = options(7); opts.workers = 4
        Dedup.open(db(), opts).withCloseable { Dedup d ->
            assert d.scan('parallel', input).hashes_completed == 73
            assert d.store.rows('SELECT entry_id,count(*) AS n FROM hashes GROUP BY entry_id HAVING count(*)>1').empty
            assert duplicates(d, 'parallel').size() == 73
        }
    }

    static class CountingHasher implements FileHasher {
        final Queue<Path> paths = new ConcurrentLinkedQueue<>()
        final Sha256Hasher delegate = new Sha256Hasher()
        String algorithm() { 'SHA-256' }
        HashValue hash(Path path, StopToken stop, int bufferBytes) {
            paths.add(path)
            delegate.hash(path, stop, bufferBytes)
        }
    }
    static class MutatingHasher extends CountingHasher {
        @Override HashValue hash(Path path, StopToken stop, int bufferBytes) {
            HashValue value = super.hash(path, stop, bufferBytes)
            if (path.fileName.toString() == 'a') {
                Instant old = Files.getLastModifiedTime(path).toInstant()
                Files.setLastModifiedTime(path, FileTime.from(old.plusSeconds(1)))
            }
            value
        }
    }
    static class SelectiveFailingHasher extends CountingHasher {
        final String failingName
        SelectiveFailingHasher(String failingName) { this.failingName = failingName }
        @Override HashValue hash(Path path, StopToken stop, int bufferBytes) {
            if (path.fileName.toString() == failingName) throw new IOException('Synthetic transient read failure')
            super.hash(path, stop, bufferBytes)
        }
    }
    static class FailingHasher implements FileHasher {
        String algorithm() { 'SHA-256' }
        HashValue hash(Path path, StopToken stop, int bufferBytes) { throw new IOException('Synthetic transient read failure') }
    }
}
