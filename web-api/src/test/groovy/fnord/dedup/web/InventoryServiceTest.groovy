package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertThrows

class InventoryServiceTest {
    @TempDir Path work

    @Test void browsesRealScansWithBoundedPagesAndHistoricalHashCoverage() {
        Path root = Files.createDirectory(work.resolve('source'))
        Path a = Files.createDirectory(root.resolve('A'))
        Path b = Files.createDirectory(root.resolve('B'))
        Files.writeString(a.resolve('same.txt'), 'same content')
        Files.writeString(b.resolve('same.txt'), 'same content')
        Files.writeString(a.resolve('unique.txt'), 'different')
        if (File.separator == '/') Files.writeString(a.resolve('name\nwith newline.txt'), 'unusual')
        Path second = Files.createDirectory(work.resolve('second'))
        Files.writeString(second.resolve('unknown.txt'), 'unhashed')
        Path db = work.resolve('inventory.duckdb')
        new DedupFixture(db).create(root, second)

        InventoryService service = new InventoryService(new ScannerDatabase(db.toString()))
        Map dashboard = service.dashboard()
        assert dashboard.scans == 2
        assert dashboard.confirmedDuplicateGroups == 1
        assert dashboard.hashesCompleted == 2
        assert dashboard.inventory.files >= 4
        assert dashboard.inventory.fileBytes == String.valueOf(
            'same content'.bytes.length * 2 + 'different'.bytes.length +
            'unhashed'.bytes.length + (File.separator == '/' ? 'unusual'.bytes.length : 0))

        Map firstPage = service.scans('1', null, null, null, null)
        assert firstPage.items*.name == ['first']
        assert firstPage.page.hasMore
        Map nextPage = service.scans('1', firstPage.page.nextCursor, null, null, null)
        assert nextPage.items*.name == ['second']
        assert !nextPage.page.hasMore
        assert service.scans('10', null, 'sec', null, null).items*.name == ['second']
        assert service.scans('10', null, null, 'COMPLETE', null).items*.name == ['first']

        Map scan = service.scan(1)
        assert scan.files >= 3
        assert scan.hashesCompleted == 2
        assert scan.confirmedDuplicateGroups == 1
        Map incomplete = service.scan(2)
        assert incomplete.files == 1
        assert incomplete.hashesCompleted == 0
        assert incomplete.phase != 'COMPLETE'

        Map rootPage = service.children(1, 1, '1', null)
        assert rootPage.directory.kind == 'DIRECTORY'
        assert rootPage.items*.filename == ['A']
        assert rootPage.page.hasMore
        Map rootNext = service.children(1, 1, '1', rootPage.page.nextCursor)
        assert rootNext.items*.filename == ['B']
        assert !rootNext.page.hasMore
        long aId = rootPage.items[0].entryId as long
        Map aChildren = service.children(1, aId, '100', null)
        assert aChildren.items*.filename.contains('same.txt')
        Map duplicate = service.entry(1, aChildren.items.find { it.filename == 'same.txt' }.entryId as long)
        assert duplicate.sha256?.size() == 64
        assert duplicate.duplicateCount == 2
        assert duplicate.path.endsWith('/A/same.txt')
        assert service.breadcrumbs(1, duplicate.entryId as long)*.name.last() == 'same.txt'
        assert service.entry(2, 2).hashState == 'UNHASHED'
        assert service.entry(2, 2).duplicateCount == null

        assertThrows(ApiFailure) { service.children(1, aId, '1', rootPage.page.nextCursor) }
        assertThrows(ApiFailure) { service.children(1, duplicate.entryId as long, null, null) }
        assertThrows(ApiFailure) { service.scan(999) }
        assertThrows(ApiFailure) { service.entry(1, 999) }
        assertThrows(ApiFailure) { service.scans('501', null, null, null, null) }
    }

    @Test void pagesErrorsAndStillRejectsConcurrentCliOwner() {
        Path root = Files.createDirectory(work.resolve('source'))
        Files.writeString(root.resolve('file'), 'some data')
        Path db = work.resolve('inventory.duckdb')
        Dedup.open(db, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('first', root)
        }
        DriverManager.getConnection('jdbc:duckdb:' + db).withCloseable { c ->
            c.prepareStatement('INSERT INTO scan_errors VALUES (?,?,?,?,?)').withCloseable { s ->
                ['first', 'second', 'third'].eachWithIndex { message, i ->
                    s.setLong(1, 1L); s.setString(2, 'DISCOVERING')
                    s.setString(3, 'file'); s.setString(4, message); s.setLong(5, 1000L + i)
                    s.executeUpdate()
                }
            }
        }
        InventoryService service = new InventoryService(new ScannerDatabase(db.toString()))
        assert service.scans('10', null, null, null, 'true').items*.name == ['first']
        assert service.scans('10', null, null, null, 'false').items.empty
        Map page = service.errors(1, '2', null)
        assert page.items*.message == ['third', 'second']
        assert page.page.hasMore
        assert service.errors(1, '2', page.page.nextCursor).items*.message == ['first']
        assertThrows(ApiFailure) { service.errors(1, '0', null) }
        new DatabaseLock(Path.of(db.toString() + '.lock')).withCloseable {
            ApiFailure blocked = assertThrows(ApiFailure) { service.dashboard() }
            assert blocked.code == 'DATABASE_LOCKED'
        }
        assert service.dashboard().scanErrors == 3
    }

    @Test void pagesConfirmedOccurrencesAcrossScansWithoutOpeningHistoricalPaths() {
        List<Path> roots = (1..3).collect { Files.createDirectory(work.resolve('source-' + it)) }
        roots.eachWithIndex { Path root, int index ->
            Files.writeString(root.resolve('a.txt'), 'same!')
            Files.writeString(root.resolve('b.txt'), 'same!')
            if (index == 1) Files.writeString(root.resolve('odd.txt'), 'same!')
        }
        Files.writeString(roots[0].resolve('different-a.txt'), 'other')
        Files.writeString(roots[0].resolve('different-b.txt'), 'other')
        Files.writeString(roots[0].resolve('unhashed.txt'), 'unique-sized unresolved payload')
        Files.write(roots[0].resolve('empty-a'), new byte[0])
        Files.write(roots[0].resolve('empty-b'), new byte[0])
        Path db = work.resolve('inventory.duckdb')
        Dedup.open(db, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable { scanner ->
            roots.eachWithIndex { Path root, int index -> scanner.scan('scan-' + (index + 1), root) }
        }
        String unusual = 'odd\\name\nλ".txt'
        DriverManager.getConnection('jdbc:duckdb:' + db).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.executeUpdate("UPDATE scans SET root='/offline/first' WHERE scan_id=1")
                s.executeUpdate("UPDATE scans SET root='C:/offline/second' WHERE scan_id=2")
                s.executeUpdate("UPDATE scans SET algorithm='OTHER' WHERE scan_id=3")
            }
            c.prepareStatement("UPDATE entries SET filename=?,relative_path=? WHERE scan_id=1 AND filename='b.txt'").withCloseable { s ->
                s.setString(1, unusual); s.setString(2, unusual); s.executeUpdate()
            }
        }
        byte[] before = MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(db))
        InventoryService service = new InventoryService(new ScannerDatabase(db.toString()))
        List<Map> children = service.children(1, 1, '500', null).items
        long referenceId = children.find { it.filename == 'a.txt' }.entryId as long
        Map reference = service.entry(1, referenceId)
        assert reference.duplicateCount == 5
        assert reference.scanId == 1 && reference.scanName == 'scan-1'
        Map first = service.occurrences(1, referenceId, '2', null)
        assert first.items.size() == 2 && first.page.hasMore
        List<Map> found = []
        String cursor = null
        do {
            Map page = service.occurrences(1, referenceId, '2', cursor)
            assert page.items.size() <= 2
            found.addAll(page.items)
            cursor = page.page.nextCursor
        } while (cursor)
        assert found.size() == 5
        assert found.collect { it.scanId + ':' + it.entryId }.unique().size() == 5
        assert found*.scanId == [1L, 1L, 2L, 2L, 2L]
        assert found.every { it.kind == 'FILE' && it.size == reference.size && it.sha256 == reference.sha256 }
        assert found.any { it.scanId == 1 && it.entryId == referenceId }
        assert found.findAll { it.scanId == 1 }.every { it.path.startsWith('/offline/first/') }
        assert found.findAll { it.scanId == 2 }.every { it.path.startsWith('C:/offline/second/') }
        Map unusualOccurrence = found.find { it.relativePath == unusual }
        assert unusualOccurrence.path == '/offline/first/' + unusual
        assert unusualOccurrence.scanName == 'scan-1' && unusualOccurrence.parentId == 1
        assert service.occurrences(1, unusualOccurrence.entryId as long, null, null).items.size() == 5
        long emptyId = children.find { it.filename == 'empty-a' }.entryId as long
        assert service.occurrences(1, emptyId, null, null).items*.size == ['0', '0']

        assert assertThrows(ApiFailure) {
            service.occurrences(1, children.find { it.filename == 'unhashed.txt' }.entryId as long, null, null)
        }.code == 'HASH_UNAVAILABLE'
        assert assertThrows(ApiFailure) { service.occurrences(1, 1, null, null) }.code == 'INVALID_FILTER'
        assert assertThrows(ApiFailure) { service.occurrences(999, referenceId, null, null) }.code == 'SCAN_NOT_FOUND'
        assert assertThrows(ApiFailure) { service.occurrences(1, 999, null, null) }.code == 'ENTRY_NOT_FOUND'
        assert assertThrows(ApiFailure) { service.occurrences(3, 2, null, null) }.code == 'UNSUPPORTED_ALGORITHM'
        assert service.entry(3, 2).duplicateCount == null
        ['0', '501', 'x'].each { limit ->
            assert assertThrows(ApiFailure) { service.occurrences(1, referenceId, limit, null) }.code == 'INVALID_FILTER'
        }
        assertThrows(ApiFailure) { service.occurrences(1, emptyId, '2', first.page.nextCursor as String) }
        assertThrows(ApiFailure) { service.occurrences(2, found.find { it.scanId == 2 }.entryId as long, '2', first.page.nextCursor as String) }
        ['invalid!', 'W10', service.children(1, 1, '1', null).page.nextCursor].each { token ->
            assert assertThrows(ApiFailure) { service.occurrences(1, referenceId, '2', token as String) }.code == 'INVALID_FILTER'
        }
        new DatabaseLock(Path.of(db.toString() + '.lock')).withCloseable {
            assert assertThrows(ApiFailure) { service.occurrences(1, referenceId, null, null) }.code == 'DATABASE_LOCKED'
        }
        assert Arrays.equals(before, MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(db)))
        assert Files.readString(roots[0].resolve('a.txt')) == 'same!'
        // A CLI hash replacement invalidates the previous content cursor.
        DriverManager.getConnection('jdbc:duckdb:' + db).withCloseable { c ->
            c.prepareStatement('UPDATE hashes SET sha256=? WHERE scan_id=1 AND entry_id=?').withCloseable { s ->
                s.setString(1, 'f' * 64); s.setLong(2, referenceId); s.executeUpdate()
            }
        }
        assert assertThrows(ApiFailure) {
            service.occurrences(1, referenceId, '2', first.page.nextCursor as String)
        }.code == 'INVALID_FILTER'
        assert service.entry(1, referenceId).duplicateCount == 1
        assert service.occurrences(1, referenceId, null, null).items*.entryId == [referenceId]
    }

    @Test void simultaneousWebReadsQueueBehindOneSidecarLock() {
        Path db = work.resolve('inventory.duckdb')
        Dedup.open(db).close()
        ScannerDatabase reader = new ScannerDatabase(db.toString())
        InventoryService service = new InventoryService(reader)
        def workers = Executors.newFixedThreadPool(2)
        CountDownLatch entered = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        try {
            def first = workers.submit {
                reader.withConnection { connection, path ->
                    entered.countDown()
                    assert release.await(5, TimeUnit.SECONDS)
                    1
                }
            }
            assert entered.await(5, TimeUnit.SECONDS)
            def second = workers.submit { service.dashboard() }
            release.countDown()
            assert first.get(5, TimeUnit.SECONDS) == 1
            assert second.get(5, TimeUnit.SECONDS).scans == 0
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private static class DedupFixture {
        final Path db
        DedupFixture(Path db) { this.db = db }
        void create(Path first, Path second) {
            Dedup.open(db, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
                it.scan('first', first)
                it.scan('second', second, new StopToken(), true)
            }
        }
    }
}
