package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertThrows

class DuplicateServiceTest {
    @TempDir Path work

    @Test void scopesConfirmedGroupsAndKeepsUnhashedFilesUnresolved() {
        Map fixture = fixture()
        DuplicateService service = fixture.service
        Map within = service.groups([scanIds: [1]])
        assert within.items.size() == 4
        assert within.items.every { it.scanCount == 1 && it.occurrences == 2 }
        assert within.items.any { it.size == '0' && it.observedBytes == '0' }
        assert within.coverage.unhashedFiles == 1
        assert within.coverage.persistedHashesOnly && within.coverage.observationsOnly

        Map across = service.groups([scanIds: [1, 2], mode: 'ACROSS_SCANS'])
        assert across.items.size() == 3
        assert across.items.every { it.scanCount == 2 && it.occurrences == 4 }
        assert across.coverage.selectedScans == 2
        assert across.coverage.files == 18
        assert across.coverage.hashedFiles == 16
        assert across.coverage.unhashedFiles == 2
        assert across.coverage.scanErrors == 1
        assert across.summary.groups == 3
        assert across.summary.occurrences == '12'
        // Equal sizes alone do not merge different digests.
        List<Map> sameSize = across.items.findAll { it.size == '4' }
        assert sameSize.size() == 2 && sameSize*.sha256.unique().size() == 2
        assert service.groups([scanIds: [1, 2], minOccurrences: 5]).items.empty
        assert service.groups([scanIds: [1, 2], minScans: 3]).items.empty
        assert service.groups([scanIds: [1, 2]]).items.size() == 5

        Map three = service.groups([scanIds: [1, 2, 3], name: [value: 'shared']])
        assert three.items.size() == 1
        assert three.items[0].occurrences == 6 && three.items[0].scanCount == 3
        assert !service.groups([scanIds: [1, 2], name: [value: 'cross-only']]).items

        // No source tree is needed for a historical report.
        Files.move(fixture.first as Path, work.resolve('offline-first'))
        assert service.groups([scanIds: [1, 2], mode: 'ACROSS_SCANS']).items*.groupId == across.items*.groupId
        assert Arrays.equals(fixture.before as byte[], digest(fixture.database as Path))
    }

    @Test void filtersSelectGroupsWithoutDiscardingOtherOccurrences() {
        Map fixture = fixture()
        DuplicateService service = fixture.service
        Map request = [scanIds: [1, 2], name: [operator: 'EXACT', value: 'shared-one.txt'],
                       path: [under: 'A'], extensions: ['TXT']]
        Map page = service.groups(request)
        assert page.items.size() == 1
        Map group = page.items[0]
        assert group.occurrences == 4 && group.matchingOccurrences == 1
        Map occurrences = service.occurrences(group.groupId as String, request)
        assert occurrences.items.size() == 4
        assert occurrences.items.count { it.matchesFilters } == 1
        assert occurrences.items*.scanName as Set == ['first', 'second'] as Set
        assert occurrences.items.every { it.sha256 == group.sha256 && it.scanRoot && it.path }
        assert occurrences.items.every { it.duplicateCount == 4 && it.duplicateScanCount == 2 }

        assert service.groups([scanIds: [1, 2], size: [exact: 0]]).items[0].size == '0'
        assert service.groups([scanIds: [1], name: [operator: 'GLOB', value: 'shared-*.txt']]).items.size() == 1
        assert service.groups([scanIds: [1], name: [operator: 'REGEX', value: '^shared-one']]).items.size() == 1
        assert service.groups([scanIds: [1], modified: [max: 0]]).items.empty
        assert service.groups([scanIds: [1, 2], errorState: 'HAS']).items[0].errorOccurrences == 1
        assert service.groups([scanIds: [1], name: [value: "x' OR 1=1 --"]]).items.empty

        Map reference = occurrences.items.find { it.filename == 'shared-one.txt' }
        Map anchored = service.groups([scanIds: [1, 2], entry: [scanId: 1, entryId: reference.entryId]])
        assert anchored.items*.groupId == [group.groupId]
        assert anchored.reference.relativePath == 'A/shared-one.txt'
        assert anchored.reference.sha256 == group.sha256
        assert anchored.items[0].occurrences == 4

        FileSearchService search = new FileSearchService(new ScannerDatabase(fixture.database.toString()))
        Map unresolved = search.search([scanIds: [1], name: [operator: 'EXACT', value: 'cross-only.bin']]).items[0]
        ApiFailure failure = assertThrows(ApiFailure) {
            service.groups([scanIds: [1, 2], entry: [scanId: 1, entryId: unresolved.entryId]])
        }
        assert failure.code == 'HASH_UNAVAILABLE'
    }

    @Test void pagesTiesInBothDirectionsAndBindsCursorsToScopeFiltersAndGroup() {
        Map fixture = fixture()
        DuplicateService service = fixture.service
        ['SIZE', 'OCCURRENCES', 'SCANS', 'OBSERVED_BYTES'].each { String field ->
            ['ASC', 'DESC'].each { String direction ->
                Map request = [scanIds: [1, 2], limit: 1, sort: [field: field, direction: direction]]
                List<String> found = []
                String cursor = null
                do {
                    Map page = service.groups(request + [cursor: cursor])
                    assert page.items.every { !it.containsKey('_sortKey') }
                    found.addAll(page.items*.groupId)
                    cursor = page.page.nextCursor
                } while (cursor)
                assert found.size() == 5 && found.unique(false).size() == 5
                assert found == service.groups(request + [limit: 500]).items*.groupId
            }
        }
        Map request = [scanIds: [1, 2], limit: 1]
        Map groups = service.groups(request)
        String groupId = groups.items[0].groupId
        Set<String> seen = [] as Set
        String cursor = null
        do {
            Map page = service.occurrences(groupId, request + [cursor: cursor])
            page.items.each { assert seen.add(it.scanId + ':' + it.entryId) }
            cursor = page.page.nextCursor
        } while (cursor)
        assert seen.size() == groups.items[0].occurrences

        String groupCursor = groups.page.nextCursor
        [request + [scanIds: [1]], request + [minOccurrences: 3],
         request + [name: [value: 'shared']], request + [sort: [field: 'SIZE', direction: 'ASC']]].each { Map changed ->
            assertThrows(ApiFailure) { service.groups(changed + [cursor: groupCursor]) }
        }
        assert service.groups(request + [limit: 2, cursor: groupCursor]).items
        String occurrenceCursor = service.occurrences(groupId, request).page.nextCursor
        assertThrows(ApiFailure) { service.occurrences(groupId, request + [cursor: groupCursor]) }
        assertThrows(ApiFailure) { service.groups(request + [cursor: occurrenceCursor]) }
        Map other = service.groups([scanIds: [1, 2], limit: 500]).items.find { it.groupId != groupId }
        assertThrows(ApiFailure) { service.occurrences(other.groupId as String, request + [cursor: occurrenceCursor]) }
        assertThrows(ApiFailure) { service.groups(request + [cursor: 'broken']) }
        assert Arrays.equals(fixture.before as byte[], digest(fixture.database as Path))
    }

    @Test void validatesRequestsAndRespectsScannerLockAndIncompleteDiscovery() {
        Map fixture = fixture()
        DuplicateService service = fixture.service
        [[:], [scanIds: [999]], [scanIds: [1], mode: 'ACROSS_SCANS'],
         [scanIds: [1], size: [min: 10, max: 1]], [scanIds: [1], limit: 501],
         [scanIds: [1], minOccurrences: 1], [scanIds: [1], minScans: 0],
         [scanIds: [1], sort: 'SIZE'], [scanIds: [1], sort: [field: 'size;DROP TABLE scans']],
         [scanIds: [1], entry: [scanId: 2, entryId: 1]], [scanIds: [1], entry: [scanId: 1, entryId: 1]],
         [scanIds: [1], entry: [scanId: 1, entryId: 999]], [scanIds: [1], unknown: true],
         [scanIds: [1], name: [operator: 'REGEX', value: '[']],
         [scanIds: [1], name: [operator: 'REGEX', value: '(?<=x)y']]].each { Map request ->
            assertThrows(ApiFailure) { service.groups(request) }
        }
        assertThrows(ApiFailure) { service.occurrences("0:' OR true--", [scanIds: [1]]) }
        String nonexistent = '0:' + ('0' * 64)
        assertThrows(ApiFailure) { service.occurrences(nonexistent, [scanIds: [1]]) }
        new DatabaseLock(Path.of(fixture.database.toString() + '.lock')).withCloseable {
            ApiFailure blocked = assertThrows(ApiFailure) { service.groups([scanIds: [1]]) }
            assert blocked.code == 'DATABASE_LOCKED'
        }
        DriverManager.getConnection('jdbc:duckdb:' + fixture.database).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.execute("UPDATE scans SET phase='DISCOVERING', active_dir=1 WHERE scan_id=2") }
        }
        assert service.groups([scanIds: [1, 2]]).coverage.incompleteScans == 1
        DriverManager.getConnection('jdbc:duckdb:' + fixture.database).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.execute("UPDATE scans SET algorithm='OTHER' WHERE scan_id=2") }
        }
        ApiFailure unsupported = assertThrows(ApiFailure) { service.groups([scanIds: [1, 2]]) }
        assert unsupported.code == 'UNSUPPORTED_ALGORITHM'
        assert service.groups([scanIds: [1]]).items
    }

    @Test void keepsLargeByteTotalsExactAndReportsForeignRootsWithoutLocalIO() {
        Map fixture = fixture()
        DriverManager.getConnection('jdbc:duckdb:' + fixture.database).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.execute("UPDATE entries SET size=9223372036854775807 WHERE filename LIKE 'shared-%'")
                s.execute("UPDATE scans SET root='C:/Offline inventory' WHERE scan_id=2")
            }
        }
        byte[] before = digest(fixture.database as Path)
        DuplicateService service = fixture.service
        Map request = [scanIds: [1, 2], limit: 1, sort: [field: 'OBSERVED_BYTES', direction: 'DESC']]
        Map first = service.groups(request)
        assert first.items[0].size == '9223372036854775807'
        assert first.items[0].observedBytes == '36893488147419103228'
        assert new BigInteger(first.summary.observedBytes as String) > BigInteger.valueOf(Long.MAX_VALUE)
        assert service.groups(request + [cursor: first.page.nextCursor]).items
        Map occurrences = service.occurrences(first.items[0].groupId as String, request + [limit: 500])
        assert occurrences.items.findAll { it.scanId == 2 }.every { it.path.startsWith('C:/Offline inventory/') }
        assert Arrays.equals(before, digest(fixture.database as Path))
    }

    private Map fixture() {
        Path first = Files.createDirectory(work.resolve('first'))
        Path a = Files.createDirectory(first.resolve('A'))
        Path b = Files.createDirectory(first.resolve('B'))
        Files.writeString(a.resolve('shared-one.txt'), 'same')
        Files.writeString(b.resolve('shared-two.txt'), 'same')
        Files.writeString(a.resolve('different-one.txt'), 'else')
        Files.writeString(b.resolve('different-two.txt'), 'else')
        Files.writeString(a.resolve('local-one.bin'), 'first local')
        Files.writeString(b.resolve('local-two.bin'), 'first local')
        Files.createFile(a.resolve('empty-one'))
        Files.createFile(b.resolve('empty-two'))
        Files.writeString(first.resolve('cross-only.bin'), 'missing cross-scan checksum')
        Path second = Files.createDirectory(work.resolve('second'))
        Files.writeString(second.resolve('shared-three.txt'), 'same')
        Files.writeString(second.resolve('shared-four.txt'), 'same')
        Files.writeString(second.resolve('different-three.txt'), 'else')
        Files.writeString(second.resolve('different-four.txt'), 'else')
        Files.writeString(second.resolve('local-three.bin'), 'second local')
        Files.writeString(second.resolve('local-four.bin'), 'second local')
        Files.createFile(second.resolve('empty-three'))
        Files.createFile(second.resolve('empty-four'))
        Files.writeString(second.resolve('cross-only.bin'), 'missing cross-scan checksum')
        Path third = Files.createDirectory(work.resolve('third'))
        Files.writeString(third.resolve('shared-five.txt'), 'same')
        Files.writeString(third.resolve('shared-six.txt'), 'same')
        Path database = work.resolve('duplicates.duckdb')
        Dedup.open(database, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('first', first); it.scan('second', second); it.scan('third', third)
        }
        DriverManager.getConnection('jdbc:duckdb:' + database).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.execute("INSERT INTO scan_errors VALUES (1,'DISCOVERING','A/shared-one.txt','fixture diagnostic',1000)")
            }
        }
        [service: new DuplicateService(new ScannerDatabase(database.toString())), database: database,
         first: first, before: digest(database)]
    }

    private static byte[] digest(Path database) {
        MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(database))
    }
}
