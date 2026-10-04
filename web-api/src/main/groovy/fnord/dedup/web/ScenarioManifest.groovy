package fnord.dedup.web

import groovy.json.JsonOutput
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import static fnord.dedup.web.ScenarioSql.*

/** One row at a time, finite private staging, and no source-file I/O. */
final class ScenarioManifest {
    static final long DEFAULT_BYTES = 64L * 1024 * 1024
    static final long MAX_BYTES = 256L * 1024 * 1024
    static final Map<String, String> MEDIA_TYPES = [JSON: 'application/json', JSONL: 'application/x-ndjson', CSV: 'text/csv']
    private static final List<String> COLUMNS = ['record_type', 'manifest_json', 'item_id', 'group_id', 'decision', 'reason',
        'observation_count', 'scan_id', 'entry_id', 'parent_id', 'scan_name_json', 'scan_root_json',
        'relative_path_json', 'filename_json', 'path_json', 'kind', 'expected_size', 'modified_sec',
        'modified_nano', 'sha256', 'matches_filters', 'protected', 'has_error', 'conflicting',
        'keep_reference_json', 'requires_revalidation']

    static Prepared prepare(Connection state, Map scenario, String format, long maxBytes, Path directory = null) {
        String exportId = UUID.randomUUID().toString()
        Map snapshot = scenario.snapshot as Map
        Map metadata = [type: 'manifest', manifestVersion: 1, exportId: exportId, format: format,
            exportedAt: Instant.now().toString(), scenario: [id: scenario.id, revision: scenario.revision.toString(),
                name: scenario.name, description: scenario.description, generationId: scenario.generationId,
                generatedAt: scenario.generatedAt, validatedAt: snapshot.validatedAt],
            sourceDatabase: [path: scenario.sourceDatabase, readOnly: true, fingerprint: snapshot.sourceFingerprint],
            sourceScans: snapshot.sourceScans.collect { Map scan -> scan + [scanId: scan.scanId.toString()] },
            algorithmVersion: snapshot.algorithmVersion, config: exportIds(scenario.config), summary: snapshot.summary,
            coverage: snapshot.coverage.collectEntries { key, value -> [key, value instanceof Long ? value.toString() : value] },
            validation: snapshot.validation,
            itemModel: 'RECORDED_PATH_PER_CONTENT',
            representativeObservation: 'Lowest scan ID, then entry ID; observationCount includes selected aliases.',
            keepReferencePolicy: 'One deterministic confirmed retained observation per removal candidate; other KEEP items remain in the manifest.',
            planningOnly: true, requires_revalidation: true,
            byteSemantics: 'Historical recorded candidate bytes; not reclaimable physical storage.',
            limits: [maxBytes: maxBytes.toString(), maxOccurrences: scenario.config.maxOccurrences,
                maxSeconds: scenario.config.maxSeconds], csvTextEncoding: format == 'CSV' ? 'JSON_STRING' : null]
        Path file = directory == null ? Files.createTempFile('fnord-scenario-export-', '.tmp') :
            Files.createTempFile(directory, 'fnord-scenario-export-', '.tmp')
        try {
            MessageDigest recordsDigest = MessageDigest.getInstance('SHA-256')
            long records = 0, observations = 0, removals = 0
            BigInteger candidateBytes = BigInteger.ZERO
            LimitedOutput output = new LimitedOutput(Files.newOutputStream(file), maxBytes)
            Map completion
            output.withCloseable {
                if (format == 'JSON') output.text('{"metadata":' + JsonOutput.toJson(metadata) + ',"items":[')
                else if (format == 'JSONL') output.text(JsonOutput.toJson(metadata) + '\n')
                else { output.text(csv(COLUMNS)); output.text(csv(['manifest', JsonOutput.toJson(metadata)])) }
                String sql = '''WITH selected AS (
                    SELECT *,row_number() OVER(PARTITION BY group_id,path_key ORDER BY scan_id,entry_id) AS ordinal,
                        count(*) OVER(PARTITION BY group_id,path_key) AS observation_count
                    FROM scenario_decisions WHERE scenario_id=? AND generation_id=?),
                    keepers AS (SELECT *,row_number() OVER(PARTITION BY group_id ORDER BY path_key,scan_id,entry_id) AS keeper_ordinal
                        FROM selected WHERE decision='KEEP' AND NOT has_error AND NOT conflicting)
                    SELECT d.*,k.scan_id AS keep_scan_id,k.entry_id AS keep_entry_id,k.parent_id AS keep_parent_id,
                        k.scan_name AS keep_scan_name,k.scan_root AS keep_scan_root,k.relative_path AS keep_relative_path,
                        k.filename AS keep_filename,k.path AS keep_path,k.path_key AS keep_path_key,
                        k.size AS keep_size,k.modified_sec AS keep_modified_sec,k.modified_nano AS keep_modified_nano,
                        k.sha256 AS keep_sha256
                    FROM selected d LEFT JOIN keepers k ON d.group_id=k.group_id AND k.keeper_ordinal=1
                    WHERE d.ordinal=1 ORDER BY d.size DESC,d.sha256,d.path_key,d.scan_id,d.entry_id'''
                state.prepareStatement(sql).withCloseable { statement ->
                    timeout(statement); bind(statement, [scenario.id, scenario.generationId])
                    statement.executeQuery().withCloseable { ResultSet r ->
                        while (r.next()) {
                            checkBudget()
                            Map item = item(r)
                            observations += r.getLong('observation_count')
                            if (observations > (scenario.config.maxOccurrences as long)) throw new ApiFailure(
                                'SCENARIO_LIMIT_EXCEEDED', HttpStatus.CONFLICT, 'The export exceeds the scenario occurrence limit.')
                            String json = JsonOutput.toJson(item)
                            recordsDigest.update((json + '\n').getBytes(StandardCharsets.UTF_8))
                            if (format == 'JSON') output.text((records ? ',' : '') + json)
                            else if (format == 'JSONL') output.text(json + '\n')
                            else output.text(csvItem(item))
                            records++
                            if (item.decision == 'REMOVE') { removals++; candidateBytes += new BigInteger(item.expected.size as String) }
                        }
                    }
                }
                if (observations.toString() != snapshot.summary.observations || removals.toString() != snapshot.summary.remove ||
                    candidateBytes.toString() != snapshot.summary.candidateBytes) throw invalidSnapshot()
                completion = [type: 'completion', exportId: exportId, complete: true, recordCount: records.toString(),
                    observationCount: observations.toString(), removalCandidates: removals.toString(),
                    candidateBytes: candidateBytes.toString(), recordsSha256: HexFormat.of().formatHex(recordsDigest.digest())]
                if (format == 'JSON') output.text('],"completion":' + JsonOutput.toJson(completion) + '}\n')
                else if (format == 'JSONL') output.text(JsonOutput.toJson(completion) + '\n')
                else output.text(csv(['completion', JsonOutput.toJson(completion)]))
                checkBudget()
            }
            new Prepared(file: file, metadata: metadata, completion: completion, bytes: output.bytes,
                sha256: HexFormat.of().formatHex(output.digest.digest()),
                filename: 'scenario-' + scenario.id + '-r' + scenario.revision + '.' + format.toLowerCase(Locale.ROOT))
        } catch (Throwable error) {
            try { Files.deleteIfExists(file) } catch (IOException cleanup) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private static Map item(ResultSet r) {
        String decision = r.getString('decision')
        boolean removal = decision == 'REMOVE'
        if (!(decision in ['KEEP', 'REMOVE', 'UNRESOLVED']) || (removal &&
            (r.getBoolean('protected') || r.getBoolean('has_error') || r.getBoolean('conflicting') ||
                !r.getBoolean('matches_filters') || r.getObject('keep_scan_id') == null ||
                r.getString('path_key') == r.getString('keep_path_key')))) throw invalidSnapshot()
        [type: 'decision', itemId: HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(
            (r.getString('group_id') + '\u0000' + r.getString('path_key')).getBytes(StandardCharsets.UTF_8))),
         groupId: r.getString('group_id'), decision: decision, reason: r.getString('reason'),
         observationCount: r.getString('observation_count'), reference: reference(r), expected: expected(r),
         matchesFilters: r.getBoolean('matches_filters'), protected: r.getBoolean('protected'),
         hasError: r.getBoolean('has_error'), conflicting: r.getBoolean('conflicting'),
         keepReference: removal ? [reference: reference(r, 'keep_'), expected: expected(r, 'keep_')] : null,
         requires_revalidation: true]
    }

    private static Map reference(ResultSet r, String prefix = '') {
        [scanId: r.getString(prefix + 'scan_id'), entryId: r.getString(prefix + 'entry_id'),
         parentId: r.getString(prefix + 'parent_id'), scanName: r.getString(prefix + 'scan_name'),
         scanRoot: r.getString(prefix + 'scan_root'), relativePath: r.getString(prefix + 'relative_path'),
         filename: r.getString(prefix + 'filename'), path: r.getString(prefix + 'path'), kind: 'FILE']
    }
    private static Object exportIds(Object value, String field = '') {
        if (value instanceof Map) return value.collectEntries { key, item -> [key, exportIds(item, key as String)] }
        if (value instanceof List) return value.collect { exportIds(it, field == 'scanIds' ? 'scanId' : field) }
        field in ['scanId', 'entryId', 'parentId'] && value != null ? value.toString() : value
    }
    private static Map expected(ResultSet r, String prefix = '') {
        [size: r.getString(prefix + 'size'), modifiedSec: r.getString(prefix + 'modified_sec'),
         modifiedNano: r.getInt(prefix + 'modified_nano'), algorithm: 'SHA-256', sha256: r.getString(prefix + 'sha256')]
    }
    private static String csvItem(Map item) {
        Map ref = item.reference as Map, expected = item.expected as Map
        csv(['decision', '', item.itemId, item.groupId, item.decision, item.reason, item.observationCount,
            ref.scanId, ref.entryId, ref.parentId, JsonOutput.toJson(ref.scanName), JsonOutput.toJson(ref.scanRoot),
            JsonOutput.toJson(ref.relativePath), JsonOutput.toJson(ref.filename), JsonOutput.toJson(ref.path), ref.kind,
            expected.size, expected.modifiedSec, expected.modifiedNano, expected.sha256, item.matchesFilters,
            item.protected, item.hasError, item.conflicting, item.keepReference ? JsonOutput.toJson(item.keepReference) : '', true])
    }
    private static String csv(List values) {
        List cells = new ArrayList(values)
        while (cells.size() < COLUMNS.size()) cells.add('')
        cells.collect { Object value -> '"' + value.toString().replace('"', '""') + '"' }.join(',') + '\r\n'
    }
    private static ApiFailure invalidSnapshot() {
        new ApiFailure('SCENARIO_NOT_READY', HttpStatus.CONFLICT,
            'The saved decisions do not form a valid export. Generate and validate the scenario again.')
    }

    static final class Prepared implements AutoCloseable {
        Path file
        Map metadata
        Map completion
        long bytes
        String sha256
        String filename
        String getMediaType() { MEDIA_TYPES[metadata.format as String] + ';charset=UTF-8' }
        @Override void close() { Files.deleteIfExists(file) }
    }

    @groovy.transform.CompileStatic
    private static final class LimitedOutput extends OutputStream {
        final OutputStream target
        final long limit
        final MessageDigest digest = MessageDigest.getInstance('SHA-256')
        long bytes
        LimitedOutput(OutputStream target, long limit) { this.target = target; this.limit = limit }
        void text(String value) { write(value.getBytes(StandardCharsets.UTF_8)) }
        @Override void write(int value) { byte[] one = [(byte) value] as byte[]; write(one, 0, 1) }
        @Override void write(byte[] value, int offset, int length) {
            checkBudget()
            if (length > limit - bytes) throw new ApiFailure('SCENARIO_EXPORT_LIMIT_EXCEEDED', HttpStatus.CONFLICT,
                'The manifest exceeds its byte limit. Narrow the scope or raise maxBytes within the server limit.')
            target.write(value, offset, length); digest.update(value, offset, length); bytes += length
        }
        @Override void close() { target.close() }
    }
}
