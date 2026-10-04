package fnord.dedup.web

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import static fnord.dedup.web.ScenarioSql.*

/** Persisted planning definitions and atomic snapshots, partitioned by scanner identity. */
@Service
class ScenarioService {
    private final WebStateStore state
    private final ScannerDatabase scanner
    private final ScenarioEngine engine

    ScenarioService(WebStateStore state, ScannerDatabase scanner, ScenarioEngine engine) {
        this.state = state; this.scanner = scanner; this.engine = engine
    }

    Map list(int limit = 50, String cursor = null) {
        int size = ScenarioConfig.integer(limit, 'limit', 1, 100) as int
        String source = scanner.sourceKey()
        Map binding = [kind: 'scenario-list-v1', source: source]
        Map position = decode(cursor, binding)
        state.withState { Connection c ->
            String sql = 'SELECT id,name,description,revision,status,created_at,updated_at FROM scenarios WHERE scanner_path=?'
            List values = [source]
            if (position) {
                if (!(position.name instanceof String) || position.name.length() > 120)
                    throw ScenarioConfig.invalid('Invalid scenario cursor.')
                String lastId = id(position.id as String)
                sql += ' AND (lower(name)>lower(?) OR (lower(name)=lower(?) AND name>?) OR (name=? AND id>?))'
                values.addAll([position.name, position.name, position.name, position.name, lastId])
            }
            sql += ' ORDER BY lower(name),name,id LIMIT ?'; values.add(size + 1)
            List items = rows(c, sql, values) { ResultSet r ->
                [id: r.getString('id'), name: r.getString('name'), description: r.getString('description'),
                 revision: r.getLong('revision'), status: r.getString('status'),
                 createdAt: r.getTimestamp('created_at').toInstant().toString(),
                 updatedAt: r.getTimestamp('updated_at').toInstant().toString()]
            }
            [items: items, page: page(items, size) { Map last -> encode(binding, [name: last.name, id: last.id]) }]
        } as Map
    }

    Map get(String scenarioId) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        state.withState { Connection c -> required(c, source, valid) } as Map
    }

    Map create(Map body) {
        Map value = ScenarioConfig.body(body)
        String source = scanner.sourceKey(), valid = UUID.randomUUID().toString()
        state.transaction { Connection c ->
            checkName(c, source, value.name as String, valid)
            exec(c, '''INSERT INTO scenarios (id,scanner_path,name,description,config_json,revision,status,
                created_at,updated_at) VALUES (?,?,?,?,?,1,'DRAFT',current_timestamp,current_timestamp)''',
                [valid, source, value.name, value.description, value.configJson])
            required(c, source, valid)
        } as Map
    }

    Map update(String scenarioId, Map body) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        Map value = ScenarioConfig.body(body, true)
        state.transaction { Connection c ->
            Map current = required(c, source, valid); revision(current, value.revision)
            checkName(c, source, value.name as String, valid)
            exec(c, '''UPDATE scenarios SET name=?,description=?,config_json=?,revision=revision+1,
                status='DRAFT',updated_at=current_timestamp WHERE id=? AND scanner_path=?''',
                [value.name, value.description, value.configJson, valid, source])
            required(c, source, valid)
        } as Map
    }

    void delete(String scenarioId, Object expectedRevision) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        state.transaction { Connection c ->
            revision(required(c, source, valid), expectedRevision)
            ['scenario_overrides', 'scenario_groups', 'scenario_decisions'].each { String table ->
                exec(c, 'DELETE FROM ' + table + ' WHERE scenario_id=?', [valid])
            }
            exec(c, 'DELETE FROM scenarios WHERE id=? AND scanner_path=?', [valid, source]); null
        }
    }

    Map generate(String scenarioId, Map body) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        ScenarioConfig.keys(body, ['revision'] as Set)
        state.transaction { Connection c ->
            Map current = required(c, source, valid); revision(current, body.revision)
            withBudget(current.config.maxSeconds as long) {
                Map snapshot = engine.generate(c, current)
                exec(c, '''UPDATE scenarios SET generation_id=?,generated_revision=revision,generated_at=current_timestamp,
                    snapshot_json=?,status=?,updated_at=current_timestamp WHERE id=? AND scanner_path=?''',
                    [snapshot.generationId, JsonOutput.toJson(snapshot), snapshot.validation.valid ? 'READY' : 'DRAFT', valid, source])
                ['scenario_groups', 'scenario_decisions'].each { String table ->
                    exec(c, 'DELETE FROM ' + table + ' WHERE scenario_id=? AND generation_id<>?', [valid, snapshot.generationId])
                }
                required(c, source, valid)
            }
        } as Map
    }

    Map validate(String scenarioId, Map body) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        ScenarioConfig.keys(body, ['revision'] as Set)
        state.transaction { Connection c ->
            Map current = required(c, source, valid); revision(current, body.revision)
            currentSnapshot(current)
            withBudget(current.config.maxSeconds as long) {
                Map snapshot = new LinkedHashMap(current.snapshot as Map)
                String fingerprint = engine.fingerprint(current.config as Map)
                Map validation = engine.validation(c, current.id as String, current.generationId as String)
                snapshot.sourceChanged = fingerprint != snapshot.sourceFingerprint
                if (snapshot.sourceChanged) {
                    validation.errors.add([code: 'SOURCE_CHANGED', message: 'The selected inventory changed. Generate a new snapshot.'])
                    validation.valid = false
                }
                snapshot.validation = validation; snapshot.validatedAt = Instant.now().toString()
                exec(c, 'UPDATE scenarios SET status=?,snapshot_json=?,updated_at=current_timestamp WHERE id=? AND scanner_path=?',
                    [validation.valid ? (current.status == 'EXPORTED' ? 'EXPORTED' : 'READY') : 'DRAFT',
                     JsonOutput.toJson(snapshot), valid, source])
                required(c, source, valid)
            }
        } as Map
    }

    /** Stage a complete bounded manifest before committing any HTTP download headers. */
    ScenarioManifest.Prepared prepareExport(String scenarioId, Map body) {
        String valid = id(scenarioId)
        ScenarioConfig.keys(body, ['revision', 'format', 'maxBytes'] as Set)
        String format = body.format
        if (!ScenarioManifest.MEDIA_TYPES.containsKey(format))
            throw ScenarioConfig.invalid('Choose JSON, JSONL, or CSV for the export format.')
        long limit = ScenarioConfig.integer(body.get('maxBytes', ScenarioManifest.DEFAULT_BYTES), 'maxBytes', 1, ScenarioManifest.MAX_BYTES)
        // Retain the state owner across revalidation and staging. Delivery uses only the immutable temporary file.
        state.withState { Connection c ->
            Map current = required(c, scanner.sourceKey(), valid); revision(current, body.revision); currentSnapshot(current)
            ScenarioManifest.Prepared prepared
            try {
                withBudget(current.config.maxSeconds as long) {
                    Map verified = validate(valid, [revision: body.revision])
                    if (!verified.snapshot.validation.valid) throw new ApiFailure('SCENARIO_NOT_READY', HttpStatus.CONFLICT,
                        'The scenario failed validation. Review its snapshot and regenerate before exporting.')
                    prepared = ScenarioManifest.prepare(c, verified, format, limit)
                }
                prepared
            } catch (Throwable error) {
                try { prepared?.close() } catch (IOException cleanup) { error.addSuppressed(cleanup) }
                if (error instanceof IOException) throw new ApiFailure('SCENARIO_EXPORT_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                    'The export could not be staged in temporary storage.', error)
                throw error
            }
        } as ScenarioManifest.Prepared
    }

    void export(String scenarioId, Map body, Closure deliver) {
        prepareExport(scenarioId, body).withCloseable { manifest ->
            // Keep status readers behind delivery/completion; the scanner lock is already released.
            state.withState { Connection ignored -> deliver.call(manifest); completeExport(manifest) }
        }
    }

    /** A delivered earlier revision must never change the status of a newer edit or generation. */
    void completeExport(ScenarioManifest.Prepared manifest) {
        Map meta = manifest.metadata
        state.transaction { Connection c ->
            List found = rows(c, 'SELECT snapshot_json FROM scenarios WHERE scanner_path=? AND id=? AND revision=? AND generation_id=?',
                [meta.sourceDatabase.path, meta.scenario.id, Long.parseLong(meta.scenario.revision as String), meta.scenario.generationId]) {
                ResultSet r -> new JsonSlurper().parseText(r.getString(1)) as Map
            }
            if (found && !found[0].sourceChanged && found[0].sourceFingerprint == meta.sourceDatabase.fingerprint) {
                Map snapshot = found[0]
                snapshot.lastExport = [exportId: meta.exportId, format: meta.format, exportedAt: meta.exportedAt,
                    revision: meta.scenario.revision, generationId: meta.scenario.generationId,
                    bytes: manifest.bytes.toString(), sha256: manifest.sha256, recordCount: manifest.completion.recordCount]
                exec(c, "UPDATE scenarios SET status='EXPORTED',snapshot_json=?,updated_at=current_timestamp WHERE id=? AND scanner_path=?",
                    [JsonOutput.toJson(snapshot), meta.scenario.id, meta.sourceDatabase.path])
            }
            null
        }
    }

    Map groups(String scenarioId, int limit = 100, String cursor = null) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        int size = ScenarioConfig.integer(limit, 'limit', 1, 500) as int
        state.withState { Connection c ->
            Map current = required(c, source, valid); snapshotExists(current)
            Map binding = snapshotBinding(current, 'scenario-groups-v1')
            Map position = decode(cursor, binding)
            String sql = 'SELECT * FROM scenario_groups WHERE scenario_id=? AND generation_id=?'
            List values = [valid, current.generationId]
            if (position) {
                long lastSize = ScenarioConfig.integer(position.size, 'cursor size', 0, Long.MAX_VALUE)
                contentId(position.size + ':' + position.sha256)
                sql += ' AND (size<? OR (size=? AND sha256>?))'
                values.addAll([lastSize, lastSize, position.sha256])
            }
            sql += ' ORDER BY size DESC,sha256 LIMIT ?'; values.add(size + 1)
            List items = rows(c, sql, values) { ResultSet r -> groupMap(r) }
            [revision: current.revision, generationId: current.generationId, stale: current.stale, items: items,
             page: page(items, size) { Map last -> encode(binding, [size: last.size, sha256: last.sha256]) }]
        } as Map
    }

    Map decisions(String scenarioId, String groupId, int limit = 100, String cursor = null) {
        String valid = id(scenarioId), source = scanner.sourceKey(), group = contentId(groupId)
        int size = ScenarioConfig.integer(limit, 'limit', 1, 500) as int
        state.withState { Connection c ->
            Map current = required(c, source, valid); snapshotExists(current)
            if (!rows(c, '''SELECT 1 FROM scenario_groups WHERE scenario_id=? AND generation_id=? AND group_id=? LIMIT 1''',
                [valid, current.generationId, group]) { true }) throw new ApiFailure('SCENARIO_GROUP_NOT_FOUND',
                HttpStatus.NOT_FOUND, 'This group is not in the scenario snapshot.')
            Map binding = snapshotBinding(current, 'scenario-decisions-v1') + [groupId: group]
            Map position = decode(cursor, binding)
            String sql = '''SELECT d.*,(SELECT o.decision FROM scenario_overrides o WHERE o.scenario_id=d.scenario_id
                AND o.path_key=d.path_key AND o.group_id=d.group_id LIMIT 1) AS manual_decision
                FROM scenario_decisions d WHERE scenario_id=? AND generation_id=? AND group_id=?'''
            List values = [valid, current.generationId, group]
            if (position) {
                if (!(position.path instanceof String) || position.path.length() > 32768 || position.path.indexOf(0) >= 0)
                    throw ScenarioConfig.invalid('Invalid scenario cursor.')
                long scan = ScenarioConfig.integer(position.scanId, 'cursor scan ID', 1, Long.MAX_VALUE)
                long entry = ScenarioConfig.integer(position.entryId, 'cursor entry ID', 1, Long.MAX_VALUE)
                sql += ' AND (path_key>? OR (path_key=? AND scan_id>?) OR (path_key=? AND scan_id=? AND entry_id>?))'
                values.addAll([position.path, position.path, scan, position.path, scan, entry])
            }
            sql += ' ORDER BY path_key,scan_id,entry_id LIMIT ?'; values.add(size + 1)
            List items = rows(c, sql, values) { ResultSet r -> decisionMap(r) }
            Map pagination = page(items, size) { Map last -> encode(binding,
                [path: last._pathKey, scanId: last.scanId, entryId: last.entryId]) }
            items.each { it.remove('_pathKey') }
            [revision: current.revision, generationId: current.generationId, stale: current.stale,
             items: items, page: pagination]
        } as Map
    }

    Map override(String scenarioId, Map body) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        ScenarioConfig.keys(body, ['revision', 'decisions'] as Set)
        if (!(body.decisions instanceof List) || !body.decisions || body.decisions.size() > 100)
            throw ScenarioConfig.invalid('Send 1..100 manual decisions.')
        state.transaction { Connection c ->
            Map current = required(c, source, valid); revision(current, body.revision); currentSnapshot(current, true)
            Map<String, String> affected = [:]
            body.decisions.each { item ->
                if (!(item instanceof Map)) throw ScenarioConfig.invalid('Each manual decision must be an object.')
                ScenarioConfig.keys(item as Map, ['scanId', 'entryId', 'decision'] as Set)
                long scan = ScenarioConfig.integer(item.scanId, 'scan ID', 1, Long.MAX_VALUE)
                long entry = ScenarioConfig.integer(item.entryId, 'entry ID', 1, Long.MAX_VALUE)
                String decision = item.decision
                if (!(decision in ['KEEP', 'REMOVE', 'UNDECIDED', 'AUTO'])) throw ScenarioConfig.invalid('Invalid manual decision.')
                List found = rows(c, '''SELECT path_key,group_id,protected,has_error,conflicting,matches_filters
                    FROM scenario_decisions WHERE scenario_id=? AND generation_id=? AND scan_id=? AND entry_id=? LIMIT 1''',
                    [valid, current.generationId, scan, entry]) { ResultSet r ->
                    [path: r.getString(1), group: r.getString(2), protected: r.getBoolean(3), error: r.getBoolean(4),
                     conflicting: r.getBoolean(5), matching: r.getBoolean(6)]
                }
                if (!found) throw ScenarioConfig.invalid('The manual decision must identify an occurrence in the current snapshot.')
                Map row = found[0]
                if (decision == 'REMOVE' && (row.protected || row.error || row.conflicting || !row.matching))
                    throw ScenarioConfig.invalid('Protected, unresolved, erroneous, or out-of-scope paths cannot be removal candidates.')
                if (affected.containsKey(row.path) && affected[row.path] != decision)
                    throw ScenarioConfig.invalid('Aliases of one recorded path must have the same decision.')
                affected[row.path as String] = decision
                if (decision == 'AUTO') exec(c, 'DELETE FROM scenario_overrides WHERE scenario_id=? AND path_key=?', [valid, row.path])
                else exec(c, '''INSERT INTO scenario_overrides VALUES (?,?,?,?,current_timestamp)
                    ON CONFLICT(scenario_id,path_key) DO UPDATE SET group_id=excluded.group_id,
                    decision=excluded.decision,updated_at=excluded.updated_at''', [valid, row.path, row.group, decision])
            }
            changed(c, source, valid); required(c, source, valid)
        } as Map
    }

    Map resetOverrides(String scenarioId, Map body) {
        String valid = id(scenarioId), source = scanner.sourceKey()
        ScenarioConfig.keys(body, ['revision'] as Set)
        state.transaction { Connection c ->
            revision(required(c, source, valid), body.revision)
            exec(c, 'DELETE FROM scenario_overrides WHERE scenario_id=?', [valid])
            changed(c, source, valid); required(c, source, valid)
        } as Map
    }

    private static void changed(Connection c, String source, String valid) {
        exec(c, "UPDATE scenarios SET revision=revision+1,status='DRAFT',updated_at=current_timestamp WHERE scanner_path=? AND id=?", [source, valid])
    }
    private static Map snapshotBinding(Map current, String kind) {
        [kind: kind, scenarioId: current.id, revision: current.revision, generationId: current.generationId]
    }
    private static void snapshotExists(Map current) {
        if (!current.generationId) throw new ApiFailure('SCENARIO_NOT_GENERATED', HttpStatus.CONFLICT,
            'Generate a decision snapshot first.')
    }
    private static void currentSnapshot(Map current, boolean manualEdit = false) {
        snapshotExists(current)
        if (current.stale && !(manualEdit && !current.definitionStale && !current.snapshot?.sourceChanged))
            throw new ApiFailure('STALE_SCENARIO', HttpStatus.CONFLICT,
            'The snapshot is stale. Generate again before validating or changing decisions.')
    }
    private static void revision(Map current, Object expected) {
        long value = ScenarioConfig.integer(expected, 'revision', 1, Long.MAX_VALUE)
        if (value != current.revision) throw new ApiFailure('SCENARIO_REVISION_CONFLICT', HttpStatus.CONFLICT,
            'This scenario changed in another request. Reload it before saving.')
    }
    private static void checkName(Connection c, String source, String name, String valid) {
        if (rows(c, 'SELECT 1 FROM scenarios WHERE scanner_path=? AND name=? AND id<>? LIMIT 1',
            [source, name, valid]) { true }) throw new ApiFailure('SCENARIO_EXISTS', HttpStatus.CONFLICT,
            'A scenario with this name already exists for this scanner database.')
    }
    private static Map required(Connection c, String source, String valid) {
        List found = rows(c, 'SELECT * FROM scenarios WHERE scanner_path=? AND id=? LIMIT 1', [source, valid]) { ResultSet r ->
            String snapshotJson = r.getString('snapshot_json')
            Map snapshot = snapshotJson ? new JsonSlurper().parseText(snapshotJson) as Map : null
            long revision = r.getLong('revision')
            Long generated = r.getObject('generated_revision') == null ? null : r.getLong('generated_revision')
            [id: r.getString('id'), name: r.getString('name'), description: r.getString('description'),
             config: new JsonSlurper().parseText(r.getString('config_json')), revision: revision,
             status: r.getString('status'), sourceDatabase: source, generationId: r.getString('generation_id'),
             generatedRevision: generated, snapshot: snapshot, stale: generated != null &&
                 (generated != revision || snapshot?.sourceChanged == true),
             definitionStale: snapshot != null && snapshot.configFingerprint !=
                 ScenarioConfig.fingerprint(new JsonSlurper().parseText(r.getString('config_json')) as Map),
             createdAt: r.getTimestamp('created_at').toInstant().toString(),
             updatedAt: r.getTimestamp('updated_at').toInstant().toString(),
             generatedAt: r.getTimestamp('generated_at')?.toInstant()?.toString()]
        }
        if (!found) throw new ApiFailure('SCENARIO_NOT_FOUND', HttpStatus.NOT_FOUND, 'Scenario not found.')
        found[0]
    }
    private static Map groupMap(ResultSet r) {
        [groupId: r.getString('group_id'), size: r.getString('size'), sha256: r.getString('sha256'),
         samplePath: r.getString('sample_path'), occurrences: r.getLong('occurrences'), recordedPaths: r.getLong('recorded_paths'),
         keep: r.getLong('keep_count'), retainedCandidates: r.getLong('keeper_count'),
         remove: r.getLong('remove_count'), undecided: r.getLong('undecided_count'),
         unresolved: r.getLong('unresolved_count'), candidateBytes: r.getString('candidate_bytes')]
    }
    private static Map decisionMap(ResultSet r) {
        [scanId: r.getLong('scan_id'), entryId: r.getLong('entry_id'), parentId: r.getLong('parent_id'),
         scanName: r.getString('scan_name'), scanRoot: r.getString('scan_root'), relativePath: r.getString('relative_path'),
         filename: r.getString('filename'), path: r.getString('path'), kind: 'FILE', size: r.getString('size'),
         sha256: r.getString('sha256'), hashState: 'HASHED', modifiedSec: r.getLong('modified_sec'), modifiedNano: r.getInt('modified_nano'),
         matchesFilters: r.getBoolean('matches_filters'), hasError: r.getBoolean('has_error'),
         protected: r.getBoolean('protected'), conflicting: r.getBoolean('conflicting'),
         decision: r.getString('decision'), reason: r.getString('reason'), autoDecision: r.getString('auto_decision'),
         manualDecision: r.getString('manual_decision'),
         _pathKey: r.getString('path_key')]
    }
}
