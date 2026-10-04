package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.store.DatabaseLock
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import static org.junit.jupiter.api.Assertions.assertThrows

class ScenarioExportTest {
    @TempDir Path work

    @Test void exportsEquivalentCompleteVersionedManifestsAndPersistsExportStatus() {
        Map f = fixture()
        String id, recordDigest
        f.state.withCloseable {
            Map created = f.service.create(body('Export "plan"\nwith Unicode é'))
            id = created.id
            Map generated = f.service.generate(id, [revision: 1])
            List<Map> formats = []
            for (String format : ['JSON', 'JSONL', 'CSV']) {
                Path staged
                f.service.export(id, [revision: 1, format: format]) { artifact ->
                    staged = artifact.file
                    assert Files.exists(staged)
                    byte[] bytes = Files.readAllBytes(staged)
                    assert artifact.bytes == bytes.length && artifact.sha256 == hex(bytes)
                    assert artifact.filename == 'scenario-' + id + '-r1.' + format.toLowerCase()
                    Map result = parse(new String(bytes, StandardCharsets.UTF_8), format)
                    assert result.metadata.manifestVersion == 1
                    assert result.metadata.scenario.revision == '1' && result.metadata.scenario.name == created.name
                    assert result.metadata.sourceDatabase.path == f.scanner.sourceKey()
                    assert result.metadata.sourceScans*.scanId == ['1', '2']
                    assert result.metadata.scenario.generatedAt == generated.generatedAt
                    assert result.metadata.scenario.validatedAt
                    assert result.metadata.sourceDatabase.fingerprint == generated.snapshot.sourceFingerprint
                    assert ScenarioConfig.normalize(result.metadata.config as Map) == generated.config
                    assert result.metadata.config.request.scanIds == ['1', '2']
                    assert result.metadata.config.protections[0].scanId == '1'
                    assert result.metadata.summary == generated.snapshot.summary
                    assert result.metadata.planningOnly && result.metadata.requires_revalidation
                    assert result.completion.complete && result.completion.recordCount == '7'
                    assert result.items.size() == 7 && result.items*.itemId.unique().size() == 7
                    assert result.items.every { it.requires_revalidation && it.reference.kind == 'FILE' }
                    result.items.findAll { it.decision == 'REMOVE' }.each { item ->
                        Map kept = result.items.find { it.reference == item.keepReference.reference }
                        assert kept && kept.decision == 'KEEP' && !kept.conflicting && !kept.hasError
                        assert item.reference.path != kept.reference.path
                        assert item.expected.size == kept.expected.size && item.expected.sha256 == kept.expected.sha256
                        assert item.keepReference.expected == kept.expected
                    }
                    assert result.items.find { it.reference.filename == 'keep.txt' }.protected
                    formats.add(result)
                }
                assert !Files.exists(staged)
                Map exported = f.service.get(id)
                assert exported.status == 'EXPORTED' && exported.revision == 1 && !exported.stale
                assert exported.snapshot.lastExport.format == format && exported.snapshot.lastExport.recordCount == '7'
                assert f.service.validate(id, [revision: 1]).status == 'EXPORTED'
            }
            assert formats[0].items == formats[1].items
            assert formats[1].items == formats[2].items
            assert formats*.completion*.recordsSha256.unique().size() == 1
            recordDigest = formats[0].completion.recordsSha256
            assert recordDigest ==~ /[0-9a-f]{64}/
        }
        Map reopened = services(f.database as Path, f.statePath as Path)
        reopened.state.withCloseable {
            assert reopened.service.get(id).snapshot.lastExport.format == 'CSV'
            assert reopened.service.get(id).status == 'EXPORTED'
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void preservesUnusualFilenamesAndEncodesCsvTextAsLosslessJsonStrings() {
        Map f = fixture()
        // Stored names are metadata on both platforms; this test never creates a Windows-invalid filename.
        String name = '=SUM(1,1)"\\back\nété\t.txt'
        DriverManager.getConnection('jdbc:duckdb:' + f.database).withCloseable { c ->
            c.prepareStatement("UPDATE entries SET filename=?,relative_path=? WHERE filename='remove.txt'").withCloseable { s ->
                s.setString(1, name); s.setString(2, name); s.executeUpdate()
            }
        }
        byte[] before = digest(f.database as Path)
        f.state.withCloseable {
            String id = f.service.create(body('Unusual')).id
            f.service.generate(id, [revision: 1])
            for (String format : ['JSON', 'JSONL', 'CSV']) {
                f.service.export(id, [revision: 1, format: format]) { artifact ->
                    String text = Files.readString(artifact.file)
                    Map result = parse(text, format)
                    Map item = result.items.find { it.reference.filename == name }
                    assert item && item.reference.relativePath == name && item.decision == 'REMOVE'
                    if (format == 'CSV') {
                        List rows = csvRows(text)
                        Map row = rows.drop(1).collect { rows[0].withIndex().collectEntries { column, index -> [column, it[index]] } }
                            .find { it.item_id == item.itemId }
                        assert row.filename_json.startsWith('"') && !row.filename_json.startsWith('=')
                        assert new JsonSlurper().parseText(row.filename_json as String) == name
                    }
                }
            }
        }
        assert Arrays.equals(before, digest(f.database as Path))
    }

    @Test void coalescesHistoricalAliasesWithoutDuplicatingRemovalTargets() {
        Map f = fixture(true)
        f.state.withCloseable {
            String id = f.service.create(body('Aliases', [request: [scanIds: [1, 3]]])).id
            Map generated = f.service.generate(id, [revision: 1])
            f.service.export(id, [revision: 1, format: 'JSONL']) { artifact ->
                Map result = parse(Files.readString(artifact.file), 'JSONL')
                assert result.items.size() == 5 && result.items*.reference*.path.unique().size() == 5
                assert result.items.every { it.observationCount == '2' && it.reference.scanId == '1' }
                assert result.completion.observationCount == '10'
                assert result.completion.removalCandidates == generated.snapshot.summary.remove
                assert result.completion.candidateBytes == generated.snapshot.summary.candidateBytes
                List lines = Files.readAllLines(artifact.file)
                assert result.completion.recordsSha256 == hex((lines.subList(1, lines.size() - 1).join('\n') + '\n').getBytes(StandardCharsets.UTF_8))
            }
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
    }

    @Test void rejectsUngeneratedStaleRevisionStaleSnapshotAndUnsafePlansBeforeDelivery() {
        Map f = fixture()
        f.state.withCloseable {
            String id = f.service.create(body('Guarded', [protections: []])).id
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'JSON']) } == 'SCENARIO_NOT_GENERATED'
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'SHELL']) } == 'INVALID_SCENARIO'
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'JSON', maxBytes: ScenarioManifest.MAX_BYTES + 1]) } == 'INVALID_SCENARIO'
            assert failure { f.service.prepareExport(id, [format: 'JSON']) } == 'INVALID_SCENARIO'
            f.service.generate(id, [revision: 1])
            f.service.update(id, body('Guarded', [protections: []]) + [revision: 1])
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'JSON']) } == 'SCENARIO_REVISION_CONFLICT'
            assert failure { f.service.prepareExport(id, [revision: 2, format: 'JSON']) } == 'STALE_SCENARIO'
            f.service.generate(id, [revision: 2])
            Map group = f.service.groups(id).items[0]
            List decisions = f.service.decisions(id, group.groupId).items
            f.service.override(id, [revision: 2, decisions: decisions.collect { [scanId: it.scanId, entryId: it.entryId, decision: 'REMOVE'] }])
            f.service.generate(id, [revision: 3])
            boolean delivered = false
            assert failure { f.service.export(id, [revision: 3, format: 'JSONL']) { delivered = true } } == 'SCENARIO_NOT_READY'
            assert !delivered && f.service.get(id).status == 'DRAFT'
            String manual = f.service.create(body('Manual', [manual: true])).id
            f.service.generate(manual, [revision: 1])
            assert failure { f.service.prepareExport(manual, [revision: 1, format: 'CSV']) } == 'SCENARIO_NOT_READY'
        }
    }

    @Test void revalidatesSourceEvidenceAndHonorsScannerLocks() {
        Map f = fixture()
        f.state.withCloseable {
            String id = f.service.create(body('Source')).id
            f.service.generate(id, [revision: 1])
            new DatabaseLock(Path.of(f.database.toString() + '.lock')).withCloseable {
                assert failure { f.service.prepareExport(id, [revision: 1, format: 'JSON']) } == 'DATABASE_LOCKED'
            }
            mutate(f.database as Path) { it.execute("UPDATE entries SET modified_nano=(modified_nano+1)%1000000000 WHERE filename='remove.txt'") }
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'JSON']) } == 'SCENARIO_NOT_READY'
            Map stale = f.service.get(id)
            assert stale.status == 'DRAFT' && stale.stale && stale.snapshot.sourceChanged
            assert stale.snapshot.validation.errors*.code.contains('SOURCE_CHANGED')
        }
    }

    @Test void cleansStagingOnLimitsAndDeliveryFailureWithoutRecordingAnExport() {
        Map f = fixture()
        f.state.withCloseable {
            String id = f.service.create(body('Limited')).id
            f.service.generate(id, [revision: 1])
            boolean delivered = false
            assert failure { f.service.export(id, [revision: 1, format: 'CSV', maxBytes: 1]) { delivered = true } } == 'SCENARIO_EXPORT_LIMIT_EXCEEDED'
            assert !delivered && f.service.get(id).status == 'READY' && !f.service.get(id).snapshot.lastExport
            f.state.withState { c ->
                assert failure { ScenarioManifest.prepare(c, f.service.get(id), 'JSON', 1, work) } == 'SCENARIO_EXPORT_LIMIT_EXCEEDED'
            }
            Files.list(work).withCloseable { assert !it.anyMatch { path -> path.fileName.toString().startsWith('fnord-scenario-export-') } }
            Path staged
            assertThrows(IOException) {
                f.service.export(id, [revision: 1, format: 'JSON']) { artifact -> staged = artifact.file; throw new IOException('simulated disconnect') }
            }
            assert !Files.exists(staged) && f.service.get(id).status == 'READY'
            f.service.export(id, [revision: 1, format: 'JSON']) { artifact -> assert artifact.bytes > 1 }
            Map last = f.service.get(id).snapshot.lastExport
            assert failure { f.service.prepareExport(id, [revision: 1, format: 'CSV', maxBytes: 1]) } == 'SCENARIO_EXPORT_LIMIT_EXCEEDED'
            assert f.service.get(id).status == 'EXPORTED' && f.service.get(id).snapshot.lastExport == last
        }
    }

    @Test void doesNotMarkEditedRegeneratedDeletedOrForeignScenariosAsExported() {
        Map f = fixture()
        String id
        f.state.withCloseable {
            id = f.service.create(body('Concurrent')).id
            f.service.generate(id, [revision: 1])
            f.service.prepareExport(id, [revision: 1, format: 'JSON']).withCloseable { artifact ->
                f.service.update(id, body('Concurrent') + [revision: 1]); f.service.completeExport(artifact)
                assert f.service.get(id).status == 'DRAFT' && f.service.get(id).revision == 2
            }
            f.service.generate(id, [revision: 2])
            f.service.prepareExport(id, [revision: 2, format: 'JSON']).withCloseable { artifact ->
                f.service.generate(id, [revision: 2]); f.service.completeExport(artifact)
                assert f.service.get(id).status == 'READY' && !f.service.get(id).snapshot.lastExport
            }
        }
        Path other = work.resolve('other.duckdb'); Files.copy(f.database as Path, other)
        Map foreign = services(other, f.statePath as Path)
        foreign.state.withCloseable {
            assert failure { foreign.service.prepareExport(id, [revision: 2, format: 'JSON']) } == 'SCENARIO_NOT_FOUND'
        }
        Map reopened = services(f.database as Path, f.statePath as Path)
        reopened.state.withCloseable {
            reopened.service.prepareExport(id, [revision: 2, format: 'JSON']).withCloseable { artifact ->
                reopened.service.delete(id, 2); reopened.service.completeExport(artifact)
                assert reopened.service.list().items.empty
            }
        }
        assert Arrays.equals(f.before as byte[], digest(f.database as Path))
        assert Arrays.equals(f.before as byte[], digest(other))
    }

    @Test void exportsOfflineForeignRootsNanosecondsAndWideIntegersExactly() {
        Map f = fixture()
        mutate(f.database as Path) { s ->
            s.execute("UPDATE entries SET size=9223372036854775807,modified_sec=-1,modified_nano=987654321 WHERE kind='FILE' AND size=4")
            s.execute("UPDATE scans SET root='C:/Offline inventory' WHERE scan_id=2")
            s.execute('UPDATE hashes SET entry_id=entry_id+10000000000000000 WHERE scan_id=2')
            s.execute("UPDATE entries SET entry_id=entry_id+10000000000000000 WHERE scan_id=2 AND kind='FILE'")
        }
        byte[] before = digest(f.database as Path)
        f.state.withCloseable {
            Map reference = new FileSearchService(f.scanner).search([scanIds: [2], name: [operator: 'EXACT', value: 'copy.txt']]).items[0]
            String id = f.service.create(body('Wide', [request: [scanIds: [1, 2], entry: [scanId: 2, entryId: reference.entryId]]])).id
            f.service.generate(id, [revision: 1])
            f.service.export(id, [revision: 1, format: 'JSON']) { artifact ->
                Map result = parse(Files.readString(artifact.file), 'JSON')
                assert result.metadata.summary.candidateBytes == '36893488147419103228'
                assert result.completion.candidateBytes == result.metadata.summary.candidateBytes
                Map item = result.items.find { it.reference.scanId == '2' }
                assert item.reference.path.startsWith('C:/Offline inventory/')
                assert Long.parseLong(item.reference.entryId as String) > 10000000000000000L
                assert result.metadata.config.request.entry.entryId == reference.entryId.toString()
                assert item.expected.size == '9223372036854775807'
                assert item.expected.modifiedSec == '-1' && item.expected.modifiedNano == 987654321
            }
        }
        assert Arrays.equals(before, digest(f.database as Path))
    }

    @Test void keepsUnresolvedObservationsVisibleWithoutUsingThemAsRemovalTargetsOrKeepers() {
        Map f = fixture(true)
        mutate(f.database as Path) { s ->
            s.execute("DELETE FROM hashes WHERE scan_id=3 AND entry_id=(SELECT entry_id FROM entries WHERE scan_id=3 AND filename='keep.txt')")
            s.execute("INSERT INTO scan_errors VALUES(1,'HASHING','other.txt','fixture error',1000)")
        }
        f.state.withCloseable {
            String id = f.service.create(body('Unresolved', [request: [scanIds: [1, 3]], protections: []])).id
            f.service.generate(id, [revision: 1])
            f.service.export(id, [revision: 1, format: 'JSONL']) { artifact ->
                Map result = parse(Files.readString(artifact.file), 'JSONL')
                assert Long.parseLong(result.metadata.coverage.unhashedFiles as String) > 0
                assert result.metadata.validation.warnings*.code.contains('UNRESOLVED_PATHS')
                assert result.items.findAll { it.hasError || it.conflicting }.every { it.decision == 'UNRESOLVED' && !it.keepReference }
                assert result.items.findAll { it.decision == 'REMOVE' }.every { item ->
                    result.items.find { it.reference == item.keepReference.reference }.decision == 'KEEP'
                }
            }
        }
    }

    private Map fixture(boolean repeated = false) {
        Path a = Files.createDirectory(work.resolve('A')), b = Files.createDirectory(work.resolve('B'))
        ['keep.txt', 'remove.txt', 'other.txt'].each { Files.writeString(a.resolve(it), 'same') }
        ['empty-a', 'empty-b'].each { Files.createFile(a.resolve(it)) }
        Files.writeString(a.resolve('unhashed'), 'An intentionally unique size without a checksum.')
        ['copy.txt', 'copy2.txt'].each { Files.writeString(b.resolve(it), 'same') }
        Path database = work.resolve('scanner.duckdb'), statePath = work.resolve('state.duckdb')
        Dedup.open(database, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('first', a); it.scan('second', b); if (repeated) it.scan('repeat', a)
        }
        services(database, statePath) + [database: database, statePath: statePath, before: digest(database)]
    }
    private static Map services(Path database, Path statePath) {
        ScannerDatabase scanner = new ScannerDatabase(database.toString())
        WebStateStore state = new WebStateStore(statePath.toString(), scanner)
        [scanner: scanner, state: state, service: new ScenarioService(state, scanner, new ScenarioEngine(new DuplicateService(scanner)))]
    }
    private static Map body(String name, Map changes = [:]) {
        [name: name, description: 'Planning only', config: [request: [scanIds: [1, 2]],
            protections: [[scanId: 1, path: 'keep.txt']]] + changes]
    }
    private static String failure(Closure action) { assertThrows(ApiFailure) { action.call() }.code }
    private static byte[] digest(Path path) { MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path)) }
    private static String hex(byte[] bytes) { HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(bytes)) }
    private static void mutate(Path path, Closure action) {
        DriverManager.getConnection('jdbc:duckdb:' + path).withCloseable { c -> c.createStatement().withCloseable { action.call(it) } }
    }
    private static Map parse(String text, String format) {
        JsonSlurper json = new JsonSlurper()
        if (format == 'JSON') return json.parseText(text) as Map
        if (format == 'JSONL') {
            List records = text.readLines().collect { json.parseText(it) }
            return [metadata: records.first(), items: records.subList(1, records.size() - 1), completion: records.last()]
        }
        List records = csvRows(text)
        List rows = records.drop(1).collect { List row -> records[0].withIndex().collectEntries { column, index -> [column, row[index]] } }
        [metadata: json.parseText(rows.first().manifest_json as String), completion: json.parseText(rows.last().manifest_json as String),
         items: rows.subList(1, rows.size() - 1).collect { Map r ->
             [type: r.record_type, itemId: r.item_id, groupId: r.group_id, decision: r.decision, reason: r.reason,
              observationCount: r.observation_count, reference: [scanId: r.scan_id, entryId: r.entry_id, parentId: r.parent_id,
                  scanName: json.parseText(r.scan_name_json as String), scanRoot: json.parseText(r.scan_root_json as String),
                  relativePath: json.parseText(r.relative_path_json as String), filename: json.parseText(r.filename_json as String),
                  path: json.parseText(r.path_json as String), kind: r.kind],
              expected: [size: r.expected_size, modifiedSec: r.modified_sec, modifiedNano: Integer.parseInt(r.modified_nano as String),
                  sha256: r.sha256, algorithm: 'SHA-256'],
              matchesFilters: r.matches_filters == 'true', protected: r.protected == 'true', hasError: r.has_error == 'true',
              conflicting: r.conflicting == 'true', requires_revalidation: r.requires_revalidation == 'true',
              keepReference: r.keep_reference_json ? json.parseText(r.keep_reference_json as String) : null]
         }]
    }
    private static List<List<String>> csvRows(String text) {
        List<List<String>> records = []; List<String> row = []; StringBuilder cell = new StringBuilder(); boolean quoted = false
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i)
            if (value == '"' as char) {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"' as char) { cell.append('"'); i++ }
                else quoted = !quoted
            } else if (!quoted && value == ',' as char) { row.add(cell.toString()); cell.setLength(0) }
            else if (!quoted && value == '\n' as char) { row.add(cell.toString()); records.add(row); row = []; cell.setLength(0) }
            else if (!quoted && value == '\r' as char) { /* CRLF separator */ }
            else cell.append(value)
        }
        assert !quoted && row.empty && cell.length() == 0
        records
    }
}
