package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertThrows

class FileSearchServiceTest {
    @TempDir Path work

    @Test void filtersHistoricalEntriesAndReportsHonestHashCoverage() {
        Fixture fixture = fixture()
        FileSearchService search = fixture.search

        Map all = search.search([limit: 100])
        assert all.items.every { it.kind == 'FILE' }
        assert all.coverage.persistedHashesOnly
        assert all.coverage.hasUnhashedFiles
        assert all.items.every { !it.containsKey('_sortKey') }

        Map exact = search.search([scanIds: [1], name: [operator: 'EXACT', value: 'same.txt']])
        assert exact.items*.filename as Set == ['Same.TXT', 'same.txt'] as Set
        Map caseExact = search.search([scanIds: [1],
            name: [operator: 'EXACT', value: 'same.txt', caseSensitive: true]])
        assert caseExact.items*.filename == ['same.txt']
        assert search.search([name: [operator: 'STARTS_WITH', value: 'uni']]).items*.filename as Set ==
            ['unique.log', 'unicode-λ.txt'] as Set
        assert search.search([name: [operator: 'ENDS_WITH', value: '.JPG']]).items*.filename == ['picture.JPG']
        assert search.search([name: [operator: 'GLOB', value: '*.txt']]).items*.filename.size() == 5
        assert search.search([name: [operator: 'REGEX', value: '^copy-(one|two)\\.txt$']])
            .items*.filename as Set == ['copy-one.txt', 'copy-two.txt'] as Set

        assert search.search([path: [contains: 'A/'], extensions: ['LOG']]).items*.filename == ['unique.log']
        assert search.search([path: [startsWith: 'B/']]).items*.relativePath == ['B/same.txt']
        assert !search.search([path: [under: 'A', excludes: ['A/nested']]])
            .items*.relativePath.contains('A/nested/deep.md')
        assert search.search([size: [exact: String.valueOf(fixture.duplicateBytes)]])
            .items*.filename.size() == 4
        assert search.search([modified: [max: 1_700_000_100L]])
            .items*.filename.contains('unique.log')

        Map directories = search.search([scanIds: [1], kinds: ['DIRECTORY'],
            name: [operator: 'EXACT', value: 'A']])
        assert directories.items.size() == 1
        long aId = directories.items[0].entryId as long
        Map immediate = search.search([directory: [scanId: 1, entryId: aId, recursive: false]])
        assert immediate.items*.relativePath as Set == ['A/Same.TXT', 'A/picture.JPG', 'A/unique.log'] as Set
        Map recursive = search.search([directory: [scanId: 1, entryId: aId, recursive: true]])
        assert recursive.items*.relativePath.contains('A/nested/deep.md')

        Map hashed = search.search([hashState: 'HASHED'])
        assert hashed.items*.filename.containsAll(['Same.TXT', 'same.txt', 'copy-one.txt', 'copy-two.txt'])
        assert hashed.items.every { it.sha256 }
        Map across = search.search([duplicate: 'ACROSS_SCANS'])
        assert across.items*.filename as Set == ['Same.TXT', 'same.txt', 'copy-one.txt', 'copy-two.txt'] as Set
        assert across.items.every { it.duplicateCount == 4L && it.duplicateScanCount == 2L }
        assert search.search([duplicate: 'COPIES_3']).items*.filename as Set == across.items*.filename as Set
        assert search.search([duplicate: 'NOT_CONFIRMED']).items*.filename.contains('unique.log')
        assert search.search([scanIds: [2]]).items.every { it.scanId == 2L }
        assert search.search([errorState: 'HAS']).items*.relativePath == ['A/unique.log']
        assert !search.search([errorState: 'NONE']).items*.relativePath.contains('A/unique.log')

        Map otherKinds = search.search([scanIds: [1], kinds: ['DIRECTORY', 'SYMLINK', 'OTHER']])
        assert otherKinds.items.any { it.kind == 'DIRECTORY' }
    }

    @Test void usesStableBoundCursorsAndRejectsInvalidInputAsData() {
        Fixture fixture = fixture()
        FileSearchService search = fixture.search
        Map request = [limit: 2, sort: [field: 'NAME', direction: 'DESC']]
        Set<String> seen = [] as Set<String>
        String cursor
        do {
            Map page = search.search(request + [cursor: cursor])
            page.items.each { assert seen.add(it.scanId + ':' + it.entryId) }
            cursor = page.page.nextCursor
        } while (cursor)
        assert seen.size() == search.search([limit: 500]).items.size()

        seen.clear(); cursor = null
        request = [limit: 2, sort: [field: 'SIZE', direction: 'ASC']]
        do {
            Map page = search.search(request + [cursor: cursor])
            page.items.each { assert seen.add(it.scanId + ':' + it.entryId) }
            cursor = page.page.nextCursor
        } while (cursor)
        assert seen.size() == search.search([limit: 500]).items.size()

        Map first = search.search([limit: 2, name: [operator: 'CONTAINS', value: '.']])
        assert first.page.nextCursor
        ApiFailure stale = assertThrows(ApiFailure) {
            search.search([limit: 2, cursor: first.page.nextCursor,
                           name: [operator: 'CONTAINS', value: 'copy']])
        }
        assert stale.code == 'INVALID_FILTER'

        assert search.search([name: [operator: 'EXACT', value: "x' OR 1=1 --"]]).items.empty
        assert search.search([limit: 500]).items
        assertThrows(ApiFailure) { search.search([name: [operator: 'REGEX', value: '[']]) }
        assertThrows(ApiFailure) { search.search([sort: [field: 'relative_path']]) }
        assertThrows(ApiFailure) { search.search([size: [min: 10, max: 2]]) }
        assertThrows(ApiFailure) { search.search([directory: [scanId: 1, entryId: 999, recursive: true]]) }
        assertThrows(ApiFailure) { search.search([scanIds: [999]]) }
        assertThrows(ApiFailure) { search.search([unexpected: true]) }
    }

    private Fixture fixture() {
        Path first = Files.createDirectory(work.resolve('first'))
        Path a = Files.createDirectory(first.resolve('A'))
        Path nested = Files.createDirectory(a.resolve('nested'))
        Path b = Files.createDirectory(first.resolve('B'))
        String duplicate = 'same persisted content'
        Files.writeString(a.resolve('Same.TXT'), duplicate)
        Files.writeString(b.resolve('same.txt'), duplicate)
        Path unique = Files.writeString(a.resolve('unique.log'), 'unique payload')
        Files.setLastModifiedTime(unique, FileTime.fromMillis(1_700_000_000_000L))
        Files.writeString(a.resolve('picture.JPG'), 'jpeg-ish')
        Files.writeString(nested.resolve('deep.md'), 'deep')
        Files.writeString(first.resolve('unicode-λ.txt'), 'lambda')

        Path second = Files.createDirectory(work.resolve('second'))
        Files.writeString(second.resolve('copy-one.txt'), duplicate)
        Files.writeString(second.resolve('copy-two.txt'), duplicate)
        Files.writeString(second.resolve('unhashed.bin'), 'unresolved and unique')

        Path database = work.resolve('search.duckdb')
        Dedup.open(database, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('first', first)
            it.scan('second', second)
        }
        DriverManager.getConnection('jdbc:duckdb:' + database).withCloseable { connection ->
            connection.prepareStatement('INSERT INTO scan_errors VALUES (?,?,?,?,?)').withCloseable { statement ->
                statement.setLong(1, 1L)
                statement.setString(2, 'DISCOVERING')
                statement.setString(3, 'A/unique.log')
                statement.setString(4, 'fixture diagnostic')
                statement.setLong(5, 1_700_000_000_000L)
                statement.executeUpdate()
            }
        }
        new Fixture(search: new FileSearchService(new ScannerDatabase(database.toString())),
                    duplicateBytes: duplicate.getBytes('UTF-8').length)
    }

    private static class Fixture {
        FileSearchService search
        int duplicateBytes
    }
}
