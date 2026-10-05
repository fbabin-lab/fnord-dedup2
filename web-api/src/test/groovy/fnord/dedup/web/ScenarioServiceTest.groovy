package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.store.DatabaseLock
import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import static org.junit.jupiter.api.Assertions.assertThrows

class ScenarioServiceTest {
    @TempDir Path work

    @Test void migratesLegacyStateAndPersistsRevisionedDefinitionsWithoutChangingScanner() {
        Map f = fixture()
        String searchId = UUID.randomUUID().toString()
        DriverManager.getConnection('jdbc:duckdb:' + f.statePath).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.execute('CREATE TABLE web_schema_info(version INTEGER NOT NULL)')
                s.execute('INSERT INTO web_schema_info VALUES(1)')
                s.execute('''CREATE TABLE saved_searches(id VARCHAR PRIMARY KEY,scanner_path VARCHAR NOT NULL,
                    name VARCHAR NOT NULL,description VARCHAR NOT NULL,request_json VARCHAR NOT NULL,
                    created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(scanner_path,name))''')
            }
            c.prepareStatement('INSERT INTO saved_searches VALUES(?,?,?,?,?,current_timestamp,current_timestamp)').withCloseable { s ->
                [searchId, f.scanner.sourceKey(), 'Legacy search', 'preserved', '{}'].eachWithIndex { v, i -> s.setObject(i + 1, v) }
                s.executeUpdate()
            }
        }
        String scenarioId
        f.state.withCloseable {
            assert f.state.get(searchId).description == 'preserved'
            Map created = f.service.create(body('First'))
            scenarioId = created.id
            assert created.revision == 1 && created.status == 'DRAFT' && !created.generationId
            assert !created.config.request.containsKey('cursor')
            assert f.service.get(scenarioId).name == 'First'
            Map changed = f.service.update(scenarioId, body('Changed') + [revision: 1])
            assert changed.revision == 2 && changed.status == 'DRAFT'
            assert failure { f.service.update(scenarioId, body('Stale') + [revision: 1]) } == 'SCENARIO_REVISION_CONFLICT'
            assert failure { f.service.create(body('Changed')) } == 'SCENARIO_EXISTS'
            assert failure { f.service.delete(scenarioId, 1) } == 'SCENARIO_REVISION_CONFLICT'
            f.state.withState { c -> assert ScenarioSql.rows(c, 'SELECT version FROM web_schema_info', []) { it.getInt(1) } == [3] }
        }
        Map reopened = services(f.database as Path, f.statePath as Path)
        reopened.state.withCloseable {
            assert reopened.service.get(scenarioId).revision == 2
            assert reopened.state.get(searchId).name == 'Legacy search'
            reopened.service.delete(scenarioId, 2)
            assert reopened.service.list().items.empty
            assert failure { reopened.service.get(scenarioId) } == 'SCENARIO_NOT_FOUND'
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void isolatesDefinitionsSnapshotsAndDeletionByScannerDatabaseIdentity() {
        Map f = fixture()
        Path otherDatabase = work.resolve('other-scanner.duckdb')
        Files.copy(f.database as Path, otherDatabase)
        String firstId, groupId, otherId
        f.state.withCloseable {
            Map created = f.service.create(body('Same name'))
            firstId = created.id
            f.service.generate(firstId, [revision: 1])
            groupId = f.service.groups(firstId).items[0].groupId
        }
        Map other = services(otherDatabase, f.statePath as Path)
        other.state.withCloseable {
            assert other.service.list().items.empty
            assert failure { other.service.get(firstId) } == 'SCENARIO_NOT_FOUND'
            assert failure { other.service.groups(firstId) } == 'SCENARIO_NOT_FOUND'
            assert failure { other.service.decisions(firstId, groupId) } == 'SCENARIO_NOT_FOUND'
            assert failure { other.service.generate(firstId, [revision: 1]) } == 'SCENARIO_NOT_FOUND'
            assert failure { other.service.update(firstId, body('Changed') + [revision: 1]) } == 'SCENARIO_NOT_FOUND'
            assert failure { other.service.delete(firstId, 1) } == 'SCENARIO_NOT_FOUND'
            otherId = other.service.create(body('Same name')).id
            assert other.service.generate(otherId, [revision: 1]).status == 'READY'
        }
        Map reopened = services(f.database as Path, f.statePath as Path)
        reopened.state.withCloseable {
            assert reopened.service.list().items*.id == [firstId]
            assert reopened.service.get(firstId).status == 'READY'
            reopened.service.delete(firstId, 1)
        }
        Map otherReopened = services(otherDatabase, f.statePath as Path)
        otherReopened.state.withCloseable {
            assert otherReopened.service.list().items*.id == [otherId]
            assert otherReopened.service.groups(otherId).items.size() == 2
            assert otherReopened.service.decisions(otherId, groupId).items
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
        assert Arrays.equals(f.before as byte[], digest(otherDatabase))
    }

    @Test void appliesOrderedRulesWithDeterministicTiesAndExactStatistics() {
        Map f = fixture()
        f.state.withCloseable {
            Map created = f.service.create(body('Prefer second', [rules: [[kind: 'PREFER_SCAN', scanId: 2], [kind: 'SHALLOWEST']]]))
            Map generated = f.service.generate(created.id, [revision: 1])
            assert generated.status == 'READY' && !generated.stale
            assert generated.snapshot.planningOnly && generated.snapshot.liveRevalidationRequired
            assert generated.snapshot.summary == [groups: 2L, observations: '7', recordedPaths: '7', keep: '2', remove: '5',
                undecided: '0', unresolved: '0', observedBytes: '20', candidateBytes: '16']
            Map group = f.service.groups(created.id).items.find { it.size == '4' }
            List decisions = f.service.decisions(created.id, group.groupId).items
            assert decisions.findAll { it.decision == 'KEEP' }*.scanId == [2L]
            assert decisions.find { it.decision == 'KEEP' }.relativePath == 'x.txt'
            Map repeated = f.service.generate(created.id, [revision: 1])
            assert repeated.snapshot.sourceFingerprint == generated.snapshot.sourceFingerprint
            assert f.service.decisions(created.id, group.groupId).items == decisions
            assert f.service.validate(created.id, [revision: 1]).status == 'READY'

            // Explicit priority order controls conflicts; fallback path/ID order remains stable.
            Map config = [rules: [[kind: 'PREFER_PATH', scanId: 1, path: 'remove'], [kind: 'PREFER_SCAN', scanId: 2]]]
            Map edited = f.service.update(created.id, body('Prefer path', config) + [revision: 1])
            assert edited.stale && edited.status == 'DRAFT'
            f.service.generate(created.id, [revision: 2])
            assert f.service.decisions(created.id, group.groupId).items.find { it.decision == 'KEEP' }.relativePath == 'remove/b.txt'
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void protectsPathsAndRestrictsTargetsToDirectoryOrSearchFiltersWhileRetainingFullGroups() {
        Map f = fixture()
        f.state.withCloseable {
            Map directory = new FileSearchService(f.scanner).search([scanIds: [1], kinds: ['DIRECTORY'],
                name: [operator: 'EXACT', value: 'remove']]).items[0]
            Map request = [scanIds: [1, 2], directory: [scanId: 1, entryId: directory.entryId, recursive: true]]
            Map preview = new DuplicateService(f.scanner).groups(request)
            assert preview.items[0].occurrences == 5 && preview.items[0].matchingOccurrences == 2
            Map created = f.service.create(body('Scoped', [request: request, protections: [[scanId: 1, path: 'remove/b.txt']]]))
            Map generated = f.service.generate(created.id, [revision: 1])
            assert generated.status == 'READY'
            assert generated.snapshot.summary.remove == '1' && generated.snapshot.summary.candidateBytes == '4'
            Map group = f.service.groups(created.id).items[0]
            List decisions = f.service.decisions(created.id, group.groupId).items
            assert decisions.size() == 5
            assert decisions.find { it.relativePath == 'remove/b.txt' }.reason == 'PROTECTED'
            assert decisions.find { it.relativePath == 'remove/c.txt' }.decision == 'REMOVE'
            assert decisions.findAll { !it.matchesFilters }.every { it.decision == 'KEEP' && it.reason == 'OUTSIDE_SCOPE' }
            Map protectedRow = decisions.find { it.protected }
            assert failure { f.service.override(created.id, [revision: 1, decisions: [[scanId: protectedRow.scanId,
                entryId: protectedRow.entryId, decision: 'REMOVE']]]) } == 'INVALID_SCENARIO'
            Map outside = decisions.find { !it.matchesFilters }
            assert failure { f.service.override(created.id, [revision: 1, decisions: [[scanId: outside.scanId,
                entryId: outside.entryId, decision: 'REMOVE']]]) } == 'INVALID_SCENARIO'
        }
    }

    @Test void coalescesOverlappingInventoriesAndPropagatesProtectionsAcrossAliases() {
        Map f = fixture(true)
        f.state.withCloseable {
            Map created = f.service.create(body('Aliases', [request: [scanIds: [1, 3]],
                protections: [[scanId: 3, path: 'keep/a.txt']]]))
            Map result = f.service.generate(created.id, [revision: 1])
            assert result.status == 'READY'
            assert result.snapshot.summary.observations == '10'
            assert result.snapshot.summary.recordedPaths == '5'
            assert result.snapshot.summary.remove == '3' && result.snapshot.summary.candidateBytes == '8'
            Map group = f.service.groups(created.id).items.find { it.size == '4' }
            List decisions = f.service.decisions(created.id, group.groupId).items
            List aliases = decisions.findAll { it.relativePath == 'keep/a.txt' }
            assert aliases.size() == 2 && aliases.every { it.protected && it.decision == 'KEEP' }
            assert decisions.groupBy { it.path }.values().every { it*.decision.unique().size() == 1 }
        }
    }

    @Test void refusesLastKeeperRemovalAndRequiresExplicitGenerationAfterManualEdits() {
        Map f = fixture()
        f.state.withCloseable {
            Map created = f.service.create(body('Manual overrides'))
            f.service.generate(created.id, [revision: 1])
            Map group = f.service.groups(created.id).items.find { it.size == '4' }
            List all = f.service.decisions(created.id, group.groupId).items
            Map changed = f.service.override(created.id, [revision: 1, decisions: all.collect {
                [scanId: it.scanId, entryId: it.entryId, decision: 'REMOVE'] }])
            assert changed.revision == 2 && changed.stale && changed.status == 'DRAFT'
            assert failure { f.service.validate(created.id, [revision: 2]) } == 'STALE_SCENARIO'
            Map unsafe = f.service.generate(created.id, [revision: 2])
            assert unsafe.status == 'DRAFT' && !unsafe.snapshot.validation.valid
            assert unsafe.snapshot.validation.errors*.code == ['NO_RETAINED_CANDIDATE']
            assert f.service.validate(created.id, [revision: 2]).status == 'DRAFT'
            Map reset = f.service.resetOverrides(created.id, [revision: 2])
            assert reset.revision == 3 && reset.stale
            assert f.service.generate(created.id, [revision: 3]).status == 'READY'

            Map manual = f.service.create(body('Manual mode', [manual: true]))
            Map draft = f.service.generate(manual.id, [revision: 1])
            assert draft.status == 'DRAFT' && draft.snapshot.summary.undecided == '7'
            assert draft.snapshot.validation.errors*.code == ['UNDECIDED_DECISIONS']
        }
    }

    @Test void detectsConflictingHistoricalPathsAndStaleInventoryWithoutReadingSources() {
        Map f = fixture(true)
        mutate(f.database as Path) { s ->
            s.execute("UPDATE hashes SET sha256='" + ('0' * 64) + "' WHERE scan_id=3 AND entry_id=(SELECT entry_id FROM entries WHERE scan_id=3 AND relative_path='keep/a.txt')")
        }
        byte[] before = digest(f.database as Path)
        Files.move(f.first as Path, work.resolve('offline'))
        f.state.withCloseable {
            Map created = f.service.create(body('History', [request: [scanIds: [1, 3]]]))
            Map result = f.service.generate(created.id, [revision: 1])
            assert result.snapshot.validation.warnings*.code == ['UNRESOLVED_PATHS']
            Map group = f.service.groups(created.id).items.find { it.size == '4' }
            List decisions = f.service.decisions(created.id, group.groupId).items
            assert decisions.findAll { it.relativePath == 'keep/a.txt' }.every {
                it.conflicting && it.decision == 'UNRESOLVED' && it.reason == 'CONFLICTING_HISTORY'
            }
            assert decisions.find { it.decision == 'KEEP' }.relativePath == 'remove/b.txt'
            assert Arrays.equals(before, digest(f.database as Path))
            mutate(f.database as Path) { s -> s.execute("UPDATE entries SET modified_nano=modified_nano+1 WHERE scan_id=1 AND filename='b.txt'") }
            Map stale = f.service.validate(created.id, [revision: 1])
            assert stale.status == 'DRAFT' && stale.stale && stale.snapshot.sourceChanged
            assert stale.snapshot.validation.errors*.code == ['SOURCE_CHANGED']
        }
    }

    @Test void rollsBackCappedAppenderGenerationsAndPreservesThePreviousPublishedSnapshot() {
        Map f = fixture()
        String scenarioId, generation
        f.state.withCloseable {
            Map created = f.service.create(body('Bounded'))
            scenarioId = created.id
            generation = f.service.generate(scenarioId, [revision: 1]).generationId
            f.service.update(scenarioId, body('Bounded', [maxOccurrences: 2]) + [revision: 1])
            assert failure { f.service.generate(scenarioId, [revision: 2]) } == 'SCENARIO_LIMIT_EXCEEDED'
            assert f.service.get(scenarioId).generationId == generation
            assert f.service.groups(scenarioId).items.size() == 2
            f.state.withState { c ->
                assert ScenarioSql.rows(c, 'SELECT count(*),count(DISTINCT generation_id) FROM scenario_decisions', []) {
                    [it.getLong(1), it.getLong(2)] } == [[7L, 1L]]
            }
        }
        Map reopened = services(f.database as Path, f.statePath as Path)
        reopened.state.withCloseable {
            assert reopened.service.get(scenarioId).generationId == generation
            assert reopened.service.get(scenarioId).stale
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void bindsBothSnapshotCursorsAndEnforcesValidationLocksAndScannerIdentity() {
        Map f = fixture()
        f.state.withCloseable {
            Map created = f.service.create(body('Paging'))
            f.service.generate(created.id, [revision: 1])
            Map first = f.service.groups(created.id, 1)
            Map second = f.service.groups(created.id, 1, first.page.nextCursor)
            assert second.items.size() == 1 && second.items[0].groupId != first.items[0].groupId
            Map group = first.items[0]
            String cursor = null
            Set seen = [] as Set
            do {
                Map page = f.service.decisions(created.id, group.groupId, 1, cursor)
                page.items.each { assert seen.add(it.scanId + ':' + it.entryId) }
                cursor = page.page.nextCursor
            } while (cursor)
            assert seen.size() == 5
            String decisionCursor = f.service.decisions(created.id, group.groupId, 1).page.nextCursor
            assert failure { f.service.decisions(created.id, second.items[0].groupId, 1, decisionCursor) } == 'INVALID_SCENARIO'
            assert failure { f.service.groups(created.id, 1, decisionCursor) } == 'INVALID_SCENARIO'
            new DatabaseLock(Path.of(f.database.toString() + '.lock')).withCloseable {
                assert failure { f.service.generate(created.id, [revision: 1]) } == 'DATABASE_LOCKED'
            }
            f.service.update(created.id, body('Paging') + [revision: 1])
            assert failure { f.service.groups(created.id, 1, first.page.nextCursor) } == 'INVALID_SCENARIO'
            assert failure { f.service.decisions(created.id, group.groupId, 1, decisionCursor) } == 'INVALID_SCENARIO'
            assertThrows(ApiFailure) { f.service.create(body('Missing scope', [request: [scanIds: []]])) }
            assertThrows(ApiFailure) { f.service.create(body('Invalid rule', [rules: [[kind: 'PREFER_SCAN', scanId: 999]]])) }
            assertThrows(ApiFailure) { f.service.create(body('Traversal', [protections: [[path: '../unsafe']]])) }
            assertThrows(ApiFailure) { f.service.create(body('Wrong type', [protections: [:]])) }
            assertThrows(ApiFailure) { f.service.create(body('Unknown config', [unknown: true])) }
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void preservesTimestampPrecisionAndLargePlanningByteTotals() {
        Map f = fixture()
        mutate(f.database as Path) { s ->
            s.execute("UPDATE entries SET size=9223372036854775807,modified_sec=1000,modified_nano=10 WHERE kind='FILE' AND filename IN('a.txt','b.txt','c.txt','x.txt','y.txt')")
            s.execute("UPDATE entries SET modified_nano=20 WHERE filename='y.txt'")
            s.execute("UPDATE scans SET root='C:/Offline inventory' WHERE scan_id=2")
        }
        byte[] before = digest(f.database as Path)
        f.state.withCloseable {
            Map created = f.service.create(body('Newest', [rules: [[kind: 'NEWEST']]]))
            Map result = f.service.generate(created.id, [revision: 1])
            assert result.status == 'READY'
            assert result.snapshot.summary.candidateBytes == '36893488147419103228'
            assert result.snapshot.summary.observedBytes == '46116860184273879035'
            Map group = f.service.groups(created.id).items[0]
            Map keeper = f.service.decisions(created.id, group.groupId).items.find { it.decision == 'KEEP' }
            assert keeper.relativePath == 'y.txt' && keeper.modifiedNano == 20 && keeper.path.startsWith('C:/')
        }
        assert Arrays.equals(before, digest(f.database as Path))
    }

    @Test void accumulatesManualChoicesBeforeRegenerationAndFlagsOverridesOutsideChangedScopes() {
        Map f = fixture()
        f.state.withCloseable {
            Map created = f.service.create(body('Accumulate'))
            f.service.generate(created.id, [revision: 1])
            Map group = f.service.groups(created.id).items[0]
            List rows = f.service.decisions(created.id, group.groupId).items
            Map oldKeeper = rows.find { it.decision == 'KEEP' }, newKeeper = rows.find { it.decision == 'REMOVE' }
            f.service.override(created.id, [revision: 1, decisions: [[scanId: newKeeper.scanId, entryId: newKeeper.entryId, decision: 'KEEP']]])
            Map edited = f.service.override(created.id, [revision: 2, decisions: [[scanId: oldKeeper.scanId, entryId: oldKeeper.entryId, decision: 'REMOVE']]])
            assert edited.revision == 3 && edited.stale && !edited.definitionStale
            assert f.service.decisions(created.id, group.groupId).items.count { it.manualDecision } == 2
            assert f.service.generate(created.id, [revision: 3]).status == 'READY'
            assert f.service.decisions(created.id, group.groupId).items.find { it.decision == 'KEEP' }.path == newKeeper.path
            f.service.update(created.id, body('Accumulate', [request: [scanIds: [1, 2], size: [exact: 0]]]) + [revision: 3])
            Map scoped = f.service.generate(created.id, [revision: 4])
            assert scoped.status == 'DRAFT' && scoped.snapshot.validation.errors*.code == ['STALE_OVERRIDES']
            f.service.resetOverrides(created.id, [revision: 4])
            assert f.service.generate(created.id, [revision: 5]).status == 'READY'
        }
    }

    @Test void leavesUnhashedAliasesAndRecordedErrorsUnresolvedAndRejectsUnfinishedDiscovery() {
        Map f = fixture(true)
        mutate(f.database as Path) { s ->
            s.execute("DELETE FROM hashes WHERE scan_id=3 AND entry_id=(SELECT entry_id FROM entries WHERE scan_id=3 AND filename='a.txt')")
            s.execute("INSERT INTO scan_errors VALUES(1,'HASHING','remove/b.txt','fixture error',1000)")
        }
        byte[] before = digest(f.database as Path)
        f.state.withCloseable {
            Map created = f.service.create(body('Unresolved', [request: [scanIds: [1, 3]]]))
            Map result = f.service.generate(created.id, [revision: 1])
            assert result.snapshot.coverage.unhashedFiles > 0
            Map group = f.service.groups(created.id).items.find { it.size == '4' }
            List rows = f.service.decisions(created.id, group.groupId).items
            assert rows.find { it.filename == 'a.txt' }.reason == 'CONFLICTING_HISTORY'
            assert rows.findAll { it.filename == 'b.txt' }.every { it.hasError && it.decision == 'UNRESOLVED' }
            assert rows.findAll { it.filename == 'c.txt' }.every { it.decision == 'KEEP' }
            assert Arrays.equals(before, digest(f.database as Path))
            mutate(f.database as Path) { it.execute("UPDATE scans SET phase='DISCOVERING',active_dir=1 WHERE scan_id=3") }
            assert failure { f.service.generate(created.id, [revision: 1]) } == 'SCENARIO_INCOMPLETE_SCANS'
            mutate(f.database as Path) { it.execute("UPDATE scans SET phase='COMPLETE',active_dir=NULL,algorithm='OTHER' WHERE scan_id=3") }
            assert failure { f.service.generate(created.id, [revision: 1]) } == 'UNSUPPORTED_ALGORITHM'
        }
    }

    @Test void cancelsRealQueriesOnDeadlineAndRollsBackPendingWebStateWrites() {
        Map f = fixture()
        f.state.withCloseable {
            Map created = f.service.create(body('Deadline'))
            assert failure {
                f.state.transaction { c -> ScenarioSql.withBudget(1L) {
                    ScenarioSql.exec(c, "INSERT INTO scenario_overrides VALUES(?,?,?,'KEEP',current_timestamp)", [created.id, '/fixture', '4:' + ('0' * 64)])
                    ScenarioSql.rows(c, '''SELECT sum(sin(a.i)*cos(b.i)) FROM range(100000000) a(i),range(100000000) b(i)''', []) { it.getDouble(1) }
                } }
            } == 'SCENARIO_TIMEOUT'
            f.state.withState { c -> assert ScenarioSql.rows(c, 'SELECT count(*) FROM scenario_overrides', []) { it.getLong(1) } == [0L] }
            // A cancelled statement does not poison the owner's next request.
            assert f.service.generate(created.id, [revision: 1]).status == 'READY'
        }
    }

    private Map fixture(boolean repeated = false) {
        Path first = Files.createDirectory(work.resolve('A'))
        Path keep = Files.createDirectory(first.resolve('keep')), remove = Files.createDirectory(first.resolve('remove'))
        ['a.txt'].each { Files.writeString(keep.resolve(it), 'same') }
        ['b.txt', 'c.txt'].each { Files.writeString(remove.resolve(it), 'same') }
        Files.createFile(first.resolve('empty-a')); Files.createFile(first.resolve('empty-b'))
        Files.writeString(first.resolve('unresolved'), 'This unique-size file has no saved checksum.')
        Path second = Files.createDirectory(work.resolve('B'))
        ['x.txt', 'y.txt'].each { Files.writeString(second.resolve(it), 'same') }
        Files.writeString(second.resolve('unresolved'), 'Another unique size that remains unresolved.')
        Path database = work.resolve('scanner.duckdb'), statePath = work.resolve('state.duckdb')
        Dedup.open(database, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('first', first); it.scan('second', second)
            if (repeated) it.scan('repeat', first)
        }
        services(database, statePath) + [database: database, statePath: statePath, first: first, before: digest(database)]
    }
    private static Map services(Path database, Path statePath) {
        ScannerDatabase scanner = new ScannerDatabase(database.toString())
        WebStateStore state = new WebStateStore(statePath.toString(), scanner)
        [scanner: scanner, state: state, service: new ScenarioService(state, scanner, new ScenarioEngine(new DuplicateService(scanner)))]
    }
    private static Map body(String name, Map changes = [:]) {
        [name: name, description: 'Planning only', config: [request: [scanIds: [1, 2]]] + changes]
    }
    private static String failure(Closure action) { assertThrows(ApiFailure) { action.call() }.code }
    private static byte[] digest(Path path) { MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path)) }
    private static void mutate(Path path, Closure action) {
        DriverManager.getConnection('jdbc:duckdb:' + path).withCloseable { c -> c.createStatement().withCloseable { action.call(it) } }
    }
}
