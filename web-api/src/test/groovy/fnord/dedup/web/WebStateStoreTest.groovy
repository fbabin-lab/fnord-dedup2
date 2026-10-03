package fnord.dedup.web

import fnord.dedup.Dedup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.assertThrows

class WebStateStoreTest {
    @TempDir Path work

    @Test void persistsCrudOutsideScannerDatabaseAndOmitsTransientCursor() {
        Path scannerPath = scanner('scanner.duckdb')
        byte[] before = digest(scannerPath)
        Path statePath = work.resolve('state.duckdb')
        ScannerDatabase scanner = new ScannerDatabase(scannerPath.toString())
        String id

        new WebStateStore(statePath.toString(), scanner).withCloseable { WebStateStore state ->
            Map created = state.create([
                name: 'Large text files', description: 'Review candidates',
                request: [limit: 250, cursor: sampleCursor(), extensions: ['txt'],
                          size: [min: '10485760'], sort: [field: 'SIZE', direction: 'DESC']]
            ])
            id = created.id
            assert created.name == 'Large text files'
            assert created.request.limit == 250
            assert created.request.size.min == 10_485_760L
            assert !created.request.containsKey('cursor')
            assert state.list().items*.id == [id]
            assert state.get(id).description == 'Review candidates'

            Map updated = state.update(id, [name: 'Large documents', description: '',
                request: [extensions: ['pdf', '.TXT'], hashState: 'UNHASHED']])
            assert updated.name == 'Large documents'
            assert updated.request.extensions == ['TXT', 'pdf']
            assert updated.request.hashState == 'UNHASHED'
            ApiFailure duplicate = assertThrows(ApiFailure) {
                state.create([name: 'Large documents', request: [:]])
            }
            assert duplicate.code == 'SAVED_SEARCH_EXISTS'
        }

        new WebStateStore(statePath.toString(), scanner).withCloseable { WebStateStore reopened ->
            assert reopened.get(id).name == 'Large documents'
            reopened.delete(id)
            assert reopened.list().items.empty
            ApiFailure missing = assertThrows(ApiFailure) { reopened.get(id) }
            assert missing.code == 'SAVED_SEARCH_NOT_FOUND'
        }
        assert Arrays.equals(digest(scannerPath), before)
    }

    @Test void serializesOneStateOwnerAndIsolatesScannerIdentities() {
        Path firstScanner = scanner('first.duckdb')
        Path secondScanner = scanner('second.duckdb')
        Path statePath = work.resolve('state.duckdb')
        WebStateStore first = new WebStateStore(statePath.toString(), new ScannerDatabase(firstScanner.toString()))
        try {
            first.create([name: 'First only', request: [:]])
            WebStateStore blocked = new WebStateStore(statePath.toString(), new ScannerDatabase(firstScanner.toString()))
            try {
                ApiFailure locked = assertThrows(ApiFailure) { blocked.list() }
                assert locked.code == 'STATE_DATABASE_LOCKED'
            } finally { blocked.close() }
        } finally { first.close() }

        new WebStateStore(statePath.toString(), new ScannerDatabase(secondScanner.toString())).withCloseable {
            assert it.list().items.empty
            it.create([name: 'Second only', request: [scanIds: []]])
        }
        new WebStateStore(statePath.toString(), new ScannerDatabase(firstScanner.toString())).withCloseable {
            assert it.list().items*.name == ['First only']
        }
    }

    @Test void rejectsStateDatabaseAliasingScannerAndInvalidBodies() {
        Path scannerPath = scanner('scanner.duckdb')
        ScannerDatabase scanner = new ScannerDatabase(scannerPath.toString())
        WebStateStore alias = new WebStateStore(scannerPath.toString(), scanner)
        try {
            ApiFailure invalidPath = assertThrows(ApiFailure) { alias.list() }
            assert invalidPath.code == 'INVALID_STATE_DATABASE'
        } finally { alias.close() }

        new WebStateStore(work.resolve('state.duckdb').toString(), scanner).withCloseable { state ->
            assertThrows(ApiFailure) { state.create([name: '', request: [:]]) }
            assertThrows(ApiFailure) { state.create([name: 'bad request', request: [limit: 999]]) }
            assertThrows(ApiFailure) { state.get('not-a-uuid') }
        }
    }

    private Path scanner(String name) {
        Path path = work.resolve(name)
        Dedup.open(path).close()
        path
    }

    private static byte[] digest(Path path) {
        MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path))
    }

    private static String sampleCursor() {
        Base64.getUrlEncoder().withoutPadding().encodeToString('{"transient":true}'.bytes)
    }
}
