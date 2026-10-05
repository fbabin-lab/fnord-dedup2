package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertThrows

class SignatureServiceTest {
    @TempDir Path work

    @Test void flagsSingletonsAndEveryDuplicateCopyWithoutChangingScannerOrSources() {
        Map f = fixture()
        f.state.withCloseable {
            InventoryController inventory = new InventoryController(f.inventory, f.service, new ArchiveService(f.scanner))
            FileSearchController search = new FileSearchController(new FileSearchService(f.scanner), f.service)
            DuplicateController duplicates = new DuplicateController(new DuplicateService(f.scanner), f.service)
            Map before = inventory.children('1', '1', '100', null)
            assert before.items.find { it.filename == 'shared-a' }.duplicateCandidate
            assert !before.items.find { it.filename == 'singleton' }.duplicateCandidate
            assert before.items.every { !it.signatureMatch && !it.removalCandidate }

            Map single = f.service.create([scanId: 1, entryId: f.single.entryId,
                tag: ' Unwanted ', memo: 'Remove even the last copy\nλ'])
            assert single.size == f.single.size && single.sha256 == f.single.sha256
            assert single.tag == 'Unwanted' && single.memo.endsWith('λ')
            Map one = inventory.entry('1', f.single.entryId.toString())
            assert one.signatureMatch && one.removalCandidate && !one.duplicateCandidate
            assert f.service.matches(single.id, '100', null).total == 1

            Map shared = f.service.create([scanId: 1, entryId: f.shared.entryId])
            Map found = f.service.matches(shared.id, '1', null)
            List<Map> copies = []
            while (true) {
                copies.addAll(found.items)
                if (!found.page.hasMore) break
                found = f.service.matches(shared.id, '1', found.page.nextCursor)
            }
            assert copies.size() == 4 && copies*.scanId.unique().size() == 2
            assert copies.every { it.removalCandidate && it.signatureMatch && it.duplicateCandidate }
            assert copies.collect { it.scanId + ':' + it.entryId }.unique().size() == 4
            Map listing = inventory.children('1', '1', '100', null)
            assert listing.items.findAll { it.filename.startsWith('shared') }.every { it.signatureMatch }
            assert !listing.items.find { it.filename == 'different' }.signatureMatch
            assert !listing.items.find { it.kind == 'DIRECTORY' }.signatureMatch
            Map searchPage = search.files([scanIds: [1], limit: 100])
            assert searchPage.items.find { it.filename == 'singleton' }.removalCandidate
            Map groups = duplicates.groups([scanIds: [1, 2]])
            Map group = groups.items.find { it.sha256 == shared.sha256 }
            assert group.signatureMatch && group.removalCandidate && group.duplicateCandidate
            assert duplicates.occurrences(group.groupId, [scanIds: [1, 2]]).items.every { it.removalCandidate }
            assert failure { f.service.create([scanId: 2, entryId: copies.find { it.scanId == 2 }.entryId]) } == 'SIGNATURE_EXISTS'

            Map cursorPage = f.service.matches(shared.id, '1', null)
            assert failure { f.service.matches(single.id, '1', cursorPage.page.nextCursor) } == 'INVALID_SIGNATURE'
            f.service.update(shared.id, [tag: 'Junk', memo: "quoted '); DROP TABLE signatures; --"])
            assert f.service.get(shared.id).tag == 'Junk'
            f.service.delete(shared.id)
            Map after = inventory.entry('1', f.shared.entryId.toString())
            assert after.duplicateCandidate && !after.signatureMatch && !after.removalCandidate
            assert Arrays.equals(f.before, digest(f.database))
            assert Files.readString(f.root.resolve('shared-a')) == 'AAAA'
        }
    }

    @Test void persistsAcrossRestartDatabaseSelectionAndFutureScans() {
        Map f = fixture()
        String id
        f.state.withCloseable {
            id = f.service.create([scanId: 1, entryId: f.single.entryId, tag: 'Discard', memo: 'Persistent']).id
            assert f.service.list('1', null).items*.id == [id]
        }
        Path future = Files.createDirectory(work.resolve('future'))
        Files.writeString(future.resolve('renamed'), Files.readString(f.root.resolve('singleton')))
        Dedup.open(f.database).withCloseable { d ->
            d.scan('future', future)
            d.hash('future', new StopToken(), false, true)
        }
        Path alternate = work.resolve('other-scanner.duckdb')
        Files.copy(f.database, alternate)
        ScannerDatabase scanner = new ScannerDatabase(alternate.toString())
        new WebStateStore(f.statePath.toString(), scanner).withCloseable { state ->
            SignatureService service = new SignatureService(state, scanner)
            assert service.get(id).memo == 'Persistent'
            assert service.matches(id, '100', null).total == 2
            assert service.matches(id, '100', null).items.every { it.removalCandidate }
            service.delete(id)
            assert service.list(null, null).items.empty
            assert failure { service.get(id) } == 'SIGNATURE_NOT_FOUND'
        }
    }

    @Test void rejectsMissingHashesNonFilesMalformedBodiesAndLockedScanner() {
        Map f = fixture()
        f.state.withCloseable {
            Map missing = new FileSearchService(f.scanner).search([scanIds: [2], hashState: 'UNHASHED']).items[0]
            assert failure { f.service.create([scanId: 2, entryId: missing.entryId]) } == 'HASH_UNAVAILABLE'
            assert failure { f.service.create([scanId: 1, entryId: 1]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.create([scanId: 1, entryId: 99999]) } == 'ENTRY_NOT_FOUND'
            assert failure { f.service.create([scanId: 1.5, entryId: 2]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.create([scanId: '1', entryId: 2]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.create([scanId: 1, entryId: f.single.entryId, size: 4]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.create([scanId: 1, entryId: f.single.entryId, tag: 123]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.create([scanId: 1, entryId: f.single.entryId, memo: 'x' * 4001]) } == 'INVALID_SIGNATURE'
            assert failure { f.service.get('1-1-1-1-1') } == 'INVALID_SIGNATURE'
            assert failure { f.service.list('501', null) } == 'INVALID_SIGNATURE'
            new DatabaseLock(Path.of(f.database.toString() + '.lock')).withCloseable {
                assert failure { f.service.create([scanId: 1, entryId: f.single.entryId]) } == 'DATABASE_LOCKED'
            }
            Map signature = f.service.create([scanId: 1, entryId: f.single.entryId])
            assert failure { f.service.update(signature.id, [sha256: '0' * 64]) } == 'INVALID_SIGNATURE'
            Map signals = f.service.entrySignals(f.single + [size: '999'])
            assert !signals.signatureMatch
            assert !f.service.entrySignals(f.single + [algorithm: 'OTHER']).signatureMatch
        }
    }

    @Test void migratesV2StateWithoutLosingSavedSearchesOrScenarioDefinitions() {
        Map f = fixture()
        String searchId = UUID.randomUUID().toString()
        String scenarioId = UUID.randomUUID().toString()
        DriverManager.getConnection('jdbc:duckdb:' + f.statePath).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.execute('CREATE TABLE web_schema_info(version INTEGER NOT NULL)')
                s.execute('INSERT INTO web_schema_info VALUES(2)')
                s.execute('''CREATE TABLE saved_searches(id VARCHAR PRIMARY KEY,scanner_path VARCHAR NOT NULL,
                    name VARCHAR NOT NULL,description VARCHAR NOT NULL,request_json VARCHAR NOT NULL,
                    created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(scanner_path,name))''')
                WebStateStore.getResourceAsStream('/web-state-v2.sql').withCloseable {
                    it.getText('UTF-8').split(';').findAll { it.trim() }.each { s.execute(it) }
                }
            }
            ScenarioSql.exec(c, 'INSERT INTO saved_searches VALUES(?,?,?,?,?,current_timestamp,current_timestamp)',
                [searchId, f.scanner.sourceKey(), 'Saved', 'Preserved', '{}'])
            ScenarioSql.exec(c, '''INSERT INTO scenarios(id,scanner_path,name,description,config_json,revision,status,created_at,updated_at)
                VALUES(?,?,?,?,?,1,'DRAFT',current_timestamp,current_timestamp)''',
                [scenarioId, f.scanner.sourceKey(), 'Plan', 'Preserved', '{}'])
        }
        f.state.withCloseable {
            assert f.service.list(null, null).items.empty
            assert f.state.get(searchId).description == 'Preserved'
            f.state.withState { c ->
                assert ScenarioSql.rows(c, 'SELECT version FROM web_schema_info', []) { it.getInt(1) } == [3]
                assert ScenarioSql.rows(c, 'SELECT id FROM scenarios', []) { it.getString(1) } == [scenarioId]
            }
        }
        assert Arrays.equals(f.before, digest(f.database))
    }

    private Map fixture() {
        Path root = Files.createDirectory(work.resolve('source'))
        Files.createDirectory(root.resolve('folder'))
        Files.writeString(root.resolve('shared-a'), 'AAAA')
        Files.writeString(root.resolve('shared-b'), 'AAAA')
        Files.writeString(root.resolve('different'), 'BBBB')
        Files.writeString(root.resolve('singleton'), 'Unique unwanted content of a different length')
        Path second = Files.createDirectory(work.resolve('second'))
        Files.writeString(second.resolve('shared-a'), 'AAAA')
        Files.writeString(second.resolve('shared-b'), 'AAAA')
        Files.writeString(second.resolve('unhashed'), 'Unique unresolved bytes without a saved hash')
        Path database = work.resolve('scanner.duckdb')
        Dedup.open(database).withCloseable { d ->
            d.scan('first', root)
            d.hash('first', new StopToken(), false, true)
            d.scan('second', second)
        }
        ScannerDatabase scanner = new ScannerDatabase(database.toString())
        InventoryService inventory = new InventoryService(scanner)
        List<Map> files = inventory.children(1, 1, '100', null).items
        Path statePath = work.resolve('state.duckdb')
        WebStateStore state = new WebStateStore(statePath.toString(), scanner)
        [root: root, database: database, statePath: statePath, scanner: scanner, inventory: inventory,
         state: state, service: new SignatureService(state, scanner), before: digest(database),
         single: files.find { it.filename == 'singleton' }, shared: files.find { it.filename == 'shared-a' }]
    }

    private static byte[] digest(Path path) { MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path)) }
    private static String failure(Closure action) { assertThrows(ApiFailure, action).code }
}
