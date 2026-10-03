package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
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
