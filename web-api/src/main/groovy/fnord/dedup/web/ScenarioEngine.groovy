package fnord.dedup.web

import fnord.dedup.path.StoredPath
import groovy.json.JsonOutput
import org.duckdb.DuckDBAppender
import org.duckdb.DuckDBConnection
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import static fnord.dedup.web.ScenarioSql.*

/** Historical planning only. Streams source rows; never opens a source file or writes scanner data. */
@Service
class ScenarioEngine {
    private final DuplicateService duplicates
    ScenarioEngine(DuplicateService duplicates) { this.duplicates = duplicates }

    Map generate(Connection state, Map scenario) {
        Map config = ScenarioConfig.normalize(scenario.config)
        withBudget(config.maxSeconds as long) { generateWithinBudget(state, scenario + [config: config]) } as Map
    }

    private Map generateWithinBudget(Connection state, Map scenario) {
        String generation = UUID.randomUUID().toString()
        Map config = ScenarioConfig.normalize(scenario.config)
        Map evidence
        DuckDBAppender appender = (state as DuckDBConnection).createAppender('main', 'scenario_decisions')
        try {
            evidence = visit(config) { ResultSet r -> append(appender, scenario.id as String, generation, r) }
        } finally { appender.close() }
        exec(state, '''UPDATE scenario_decisions d SET decision=o.decision,reason='MANUAL'
            FROM scenario_overrides o WHERE d.scenario_id=? AND d.generation_id=?
            AND o.scenario_id=d.scenario_id AND o.path_key=d.path_key AND o.group_id=d.group_id
            AND (o.decision='KEEP' OR (NOT d.protected AND NOT d.has_error AND NOT d.conflicting AND d.matches_filters))''',
            [scenario.id, generation])
        exec(state, '''INSERT INTO scenario_groups
            SELECT scenario_id,generation_id,group_id,size,sha256,min(relative_path),count(*),count(DISTINCT path_key),
                count(DISTINCT path_key) FILTER (WHERE decision='KEEP'),
                count(DISTINCT path_key) FILTER (WHERE decision='KEEP' AND NOT has_error AND NOT conflicting),
                count(DISTINCT path_key) FILTER (WHERE decision='REMOVE'),
                count(DISTINCT path_key) FILTER (WHERE decision='UNDECIDED'),
                count(DISTINCT path_key) FILTER (WHERE decision='UNRESOLVED'),
                size::HUGEINT * count(DISTINCT path_key) FILTER (WHERE decision='REMOVE')
            FROM scenario_decisions WHERE scenario_id=? AND generation_id=?
            GROUP BY scenario_id,generation_id,group_id,size,sha256''', [scenario.id, generation])
        Map summary = rows(state, '''SELECT count(*) AS groups,coalesce(sum(occurrences),0) AS observations,
            coalesce(sum(recorded_paths),0) AS paths,coalesce(sum(keep_count),0) AS keeps,
            coalesce(sum(remove_count),0) AS removes,coalesce(sum(undecided_count),0) AS undecided,
            coalesce(sum(unresolved_count),0) AS unresolved,
            coalesce(sum(size::HUGEINT*occurrences),0) AS observed_bytes,
            coalesce(sum(candidate_bytes),0) AS candidate_bytes
            FROM scenario_groups WHERE scenario_id=? AND generation_id=?''', [scenario.id, generation]) { ResultSet r ->
            [groups: r.getLong('groups'), observations: r.getString('observations'), recordedPaths: r.getString('paths'),
             keep: r.getString('keeps'), remove: r.getString('removes'), undecided: r.getString('undecided'),
             unresolved: r.getString('unresolved'), observedBytes: r.getString('observed_bytes'),
             candidateBytes: r.getString('candidate_bytes')]
        }[0]
        Map uniquePaths = rows(state, '''SELECT count(DISTINCT path_key) AS paths,
            count(DISTINCT path_key) FILTER (WHERE decision='KEEP') AS keeps,
            count(DISTINCT path_key) FILTER (WHERE decision='REMOVE') AS removes,
            count(DISTINCT path_key) FILTER (WHERE decision='UNDECIDED') AS undecided,
            count(DISTINCT path_key) FILTER (WHERE decision='UNRESOLVED') AS unresolved
            FROM scenario_decisions WHERE scenario_id=? AND generation_id=?''', [scenario.id, generation]) { ResultSet r ->
            [recordedPaths: r.getString('paths'), keep: r.getString('keeps'), remove: r.getString('removes'),
             undecided: r.getString('undecided'), unresolved: r.getString('unresolved')]
        }[0]
        summary.putAll(uniquePaths)
        [generationId: generation, algorithmVersion: 'scenario-v1', configFingerprint: ScenarioConfig.fingerprint(config),
         sourceFingerprint: evidence.fingerprint,
         sourceScans: evidence.scans, coverage: evidence.coverage, summary: summary,
         validation: validation(state, scenario.id as String, generation), sourceChanged: false,
         planningOnly: true, liveRevalidationRequired: true]
    }

    String fingerprint(Map config) {
        Map normalized = ScenarioConfig.normalize(config)
        withBudget(normalized.maxSeconds as long) {
            visit(normalized) { ResultSet ignored -> }.fingerprint
        } as String
    }

    Map validation(Connection state, String scenarioId, String generation) {
        List<Map> errors = []
        long unsafe = count(state, '''SELECT count(*) FROM scenario_decisions WHERE scenario_id=? AND generation_id=?
            AND decision='REMOVE' AND (protected OR has_error OR conflicting OR NOT matches_filters
            OR size<0 OR NOT regexp_full_match(sha256,'[0-9a-f]{64}'))''', [scenarioId, generation])
        if (unsafe) errors.add([code: 'UNSAFE_REMOVAL', count: unsafe,
            message: 'Protected, unresolved, erroneous, or out-of-scope paths cannot be removal candidates.'])
        long missingKeeper = count(state, '''SELECT count(*) FROM scenario_groups WHERE scenario_id=? AND generation_id=?
            AND remove_count>0 AND keeper_count=0''', [scenarioId, generation])
        if (missingKeeper) errors.add([code: 'NO_RETAINED_CANDIDATE', count: missingKeeper,
            message: 'Every group with removal candidates must retain a confirmed regular-file candidate.'])
        long undecided = count(state, '''SELECT count(DISTINCT path_key) FROM scenario_decisions
            WHERE scenario_id=? AND generation_id=? AND decision='UNDECIDED' ''', [scenarioId, generation])
        if (undecided) errors.add([code: 'UNDECIDED_DECISIONS', count: undecided,
            message: 'Resolve the remaining manual decisions before this plan is ready.'])
        long stale = count(state, '''SELECT count(*) FROM scenario_overrides o WHERE o.scenario_id=? AND NOT EXISTS(
            SELECT 1 FROM scenario_decisions d WHERE d.scenario_id=o.scenario_id AND d.generation_id=?
            AND d.path_key=o.path_key AND d.group_id=o.group_id)''', [scenarioId, generation])
        if (stale) errors.add([code: 'STALE_OVERRIDES', count: stale,
            message: 'Some manual decisions no longer match this scope or content. Reset them before continuing.'])
        long unresolved = count(state, '''SELECT count(DISTINCT path_key) FROM scenario_decisions
            WHERE scenario_id=? AND generation_id=? AND (conflicting OR has_error)''', [scenarioId, generation])
        [valid: errors.empty, errors: errors, warnings: unresolved ? [[code: 'UNRESOLVED_PATHS', count: unresolved,
            message: 'Conflicting history or recorded errors prevent decisions for these paths.']] : []]
    }

    private Map visit(Map config, Closure consume) {
        Map spec = DuplicateService.normalize(config.request as Map)
        duplicates.withSelection(spec) { Connection source, Map context ->
            Map coverage = DuplicateService.coverage(source, spec)
            if (coverage.incompleteScans) throw new ApiFailure('SCENARIO_INCOMPLETE_SCANS', HttpStatus.CONFLICT,
                'Finish discovery for all selected scans before generating a scenario.')
            List scans = sourceScans(source, spec)
            audit(source, spec)
            Map query = decisionQuery(context.query as Map, spec, config)
            MessageDigest digest = MessageDigest.getInstance('SHA-256')
            hash(digest, [version: 1, request: config.request, scans: scans, coverage: coverage])
            long count = 0L
                source.prepareStatement(query.sql as String).withCloseable { statement ->
                    ScenarioSql.timeout(statement)
                    bind(statement, query.values as List)
                    try {
                        statement.executeQuery().withCloseable { ResultSet result ->
                            while (result.next()) {
                                checkBudget()
                                if (++count > (config.maxOccurrences as long)) throw new ApiFailure('SCENARIO_LIMIT_EXCEEDED',
                                    HttpStatus.CONFLICT, 'The scenario exceeds its occurrence limit. Narrow the scope; no partial snapshot was published.')
                                try { StoredPath.validateRelative(result.getString('scan_root'), result.getString('relative_path')) }
                                catch (IllegalArgumentException ignored) { throw invalidInventory() }
                                hash(digest, [scanId: result.getLong('scan_id'), entryId: result.getLong('entry_id'),
                                    parentId: result.getLong('parent_id'), path: result.getString('path'),
                                    size: result.getString('size'), modifiedSec: result.getLong('modified_sec'),
                                    modifiedNano: result.getInt('modified_nano'), sha256: result.getString('sha256'),
                                    matching: result.getBoolean('filter_match'), error: result.getBoolean('has_error'),
                                    conflicting: result.getBoolean('conflicting')])
                                consume.call(result)
                            }
                            checkBudget()
                        }
                    } catch (SQLException error) {
                        checkBudget()
                        throw error
                    }
                }
            [fingerprint: HexFormat.of().formatHex(digest.digest()), coverage: coverage, scans: scans]
        } as Map
    }

    private static Map decisionQuery(Map base, Map spec, Map config) {
        List values = new ArrayList(base.values as List)
        String ids = (['?'] * spec.scanIds.size()).join(',')
        values.addAll(spec.scanIds)
        String protection = 'false'
        List<String> protectedPaths = []
        config.protections.each { Map path ->
            String clause = prefix('relative_path', path.path as String, values)
            if (path.scanId != null) { clause = '(' + clause + ' AND scan_id=?)'; values.add(path.scanId) }
            protectedPaths.add(clause)
        }
        if (protectedPaths) protection = protectedPaths.join(' OR ')
        List<String> order = ["CASE WHEN conflicting OR path_error THEN 1 ELSE 0 END",
            'CASE WHEN is_protected OR NOT target THEN 0 ELSE 1 END']
        config.rules.each { Map rule ->
            switch (rule.kind) {
                case 'PREFER_SCAN': order.add('CASE WHEN scan_id=? THEN 0 ELSE 1 END'); values.add(rule.scanId); break
                case 'PREFER_PATH':
                    String clause = prefix('relative_path', rule.path as String, values)
                    if (rule.scanId != null) { clause = '(' + clause + ' AND scan_id=?)'; values.add(rule.scanId) }
                    order.add('CASE WHEN ' + clause + ' THEN 0 ELSE 1 END'); break
                case 'NEWEST': order.add('modified_sec DESC'); order.add('modified_nano DESC'); break
                case 'OLDEST': order.add('modified_sec ASC'); order.add('modified_nano ASC'); break
                case 'SHALLOWEST': order.add("length(relative_path)-length(replace(relative_path,'/','')) ASC"); break
            }
        }
        order.addAll(['path_key', 'scan_id', 'entry_id'])
        String path = "scan_root || CASE WHEN ends_with(scan_root,'/') THEN '' ELSE '/' END || relative_path"
        String decision = '''CASE WHEN is_protected THEN 'KEEP' WHEN NOT target THEN 'KEEP'
            WHEN conflicting OR path_error OR NOT has_keeper THEN 'UNRESOLVED' ''' +
            (config.manual ? "WHEN true THEN 'UNDECIDED' " : '') +
            "WHEN path_key=keep_key THEN 'KEEP' ELSE 'REMOVE' END"
        String reason = '''CASE WHEN is_protected THEN 'PROTECTED' WHEN NOT target THEN 'OUTSIDE_SCOPE'
            WHEN conflicting THEN 'CONFLICTING_HISTORY' WHEN path_error THEN 'RECORDED_ERROR'
            WHEN NOT has_keeper THEN 'NO_CONFIRMED_KEEPER' ''' +
            (config.manual ? "WHEN true THEN 'MANUAL_REQUIRED' " : '') +
            "WHEN path_key=keep_key THEN 'RULE_KEEPER' ELSE 'RULE_REDUNDANT' END"
        String sql = base.sql + ''', all_named AS (
            SELECT e.*,h.sha256,s.root AS scan_root FROM entries e JOIN scans s USING(scan_id)
            LEFT JOIN hashes h USING(scan_id,entry_id) WHERE e.kind='FILE' AND e.scan_id IN (''' + ids + ''')),
            all_paths AS (SELECT *,''' + path + ''' AS path FROM all_named),
            all_identities AS (SELECT *,''' + identity('path') + ''' AS path_key FROM all_paths),
            history AS (SELECT path_key,bool_or(sha256 IS NULL) OR
                count(DISTINCT size::VARCHAR || ':' || coalesce(sha256,'UNHASHED'))>1 AS conflicting
                FROM all_identities GROUP BY path_key),
            matched_paths AS (SELECT o.*,o.size::VARCHAR || ':' || o.sha256 AS group_id,''' + path + ''' AS path
                FROM observations o JOIN matches m USING(size,sha256)),
            identified AS (SELECT *,''' + identity('path') + ''' AS path_key FROM matched_paths),
            protected_rows AS (SELECT *,(''' + protection + ''') AS row_protected FROM identified),
            flags AS (SELECT group_id,path_key,bool_and(filter_match) AS target,
                bool_or(row_protected) AS is_protected,bool_or(has_error) AS path_error
                FROM protected_rows GROUP BY group_id,path_key),
            checked AS (SELECT p.*,f.target,f.is_protected,f.path_error,h.conflicting
                FROM protected_rows p JOIN flags f USING(group_id,path_key) JOIN history h USING(path_key)),
            chosen AS (SELECT *,first_value(path_key) OVER(PARTITION BY group_id ORDER BY ''' + order.join(',') + ''') AS keep_key,
                bool_or(NOT conflicting AND NOT path_error) OVER(PARTITION BY group_id) AS has_keeper FROM checked)
            SELECT *,''' + decision + ' AS auto_decision,' + reason + ''' AS auto_reason FROM chosen
            ORDER BY size DESC,sha256,scan_id,relative_path,entry_id'''
        [sql: sql, values: values]
    }

    private static String identity(String column) {
        'CASE WHEN ' + windowsRoot() + ' THEN lower(' + column + ') ELSE ' + column + ' END'
    }
    private static String windowsRoot() { "regexp_matches(scan_root,'^[A-Za-z]:/') OR starts_with(scan_root,'//')" }
    private static String prefix(String column, String path, List values) {
        if (!path) return 'true'
        String folded = identity(column)
        String value = 'CASE WHEN ' + windowsRoot() + ' THEN lower(?) ELSE ? END'
        values.addAll([path, path, path, path])
        '(' + folded + '=' + value + ' OR starts_with(' + folded + ',' + value + " || '/'))"
    }
    private static List sourceScans(Connection source, Map spec) {
        rows(source, 'SELECT scan_id,name,root,phase,algorithm,updated_at FROM scans WHERE scan_id IN (' +
            (['?'] * spec.scanIds.size()).join(',') + ') ORDER BY scan_id', spec.scanIds as List) { ResultSet r ->
            String root = r.getString('root')
            try { StoredPath.style(root) } catch (IllegalArgumentException ignored) { throw invalidInventory() }
            [scanId: r.getLong('scan_id'), name: r.getString('name'), root: root, phase: r.getString('phase'),
             algorithm: r.getString('algorithm'), updatedAt: r.getTimestamp('updated_at').toInstant().toString()]
        }
    }
    private static void audit(Connection source, Map spec) {
        String ids = (['?'] * spec.scanIds.size()).join(',')
        long invalid = count(source, '''SELECT count(*) FROM entries e LEFT JOIN hashes h USING(scan_id,entry_id)
            WHERE e.scan_id IN (''' + ids + ''') AND e.kind='FILE' AND (e.size<0 OR e.entry_id<1
            OR (h.sha256 IS NOT NULL AND NOT regexp_full_match(h.sha256,'[0-9a-f]{64}')))''', spec.scanIds as List)
        for (String table : ['entries', 'hashes']) {
            invalid += count(source, 'SELECT count(*) FROM (SELECT scan_id,entry_id FROM ' + table +
                ' WHERE scan_id IN (' + ids + ') GROUP BY scan_id,entry_id HAVING count(*)>1)', spec.scanIds as List)
        }
        if (invalid) throw invalidInventory()
    }
    private static long count(Connection c, String sql, List values) {
        rows(c, sql, values) { ResultSet r -> r.getLong(1) }[0] as long
    }
    private static void hash(MessageDigest digest, Object value) {
        digest.update(JsonOutput.toJson(value).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0)
    }
    private static void append(DuckDBAppender a, String scenarioId, String generation, ResultSet r) {
        a.beginRow()
        a.append(scenarioId); a.append(generation); a.append(r.getString('group_id'))
        a.append(r.getLong('scan_id')); a.append(r.getLong('entry_id')); a.append(r.getLong('parent_id'))
        a.append(r.getString('scan_name')); a.append(r.getString('scan_root')); a.append(r.getString('relative_path'))
        a.append(r.getString('filename')); a.append(r.getString('path')); a.append(r.getString('path_key'))
        a.append(r.getLong('size')); a.append(r.getLong('modified_sec')); a.append(r.getInt('modified_nano'))
        a.append(r.getString('sha256')); a.append(r.getBoolean('target')); a.append(r.getBoolean('path_error'))
        a.append(r.getBoolean('is_protected')); a.append(r.getBoolean('conflicting'))
        a.append(r.getString('auto_decision')); a.append(r.getString('auto_reason'))
        a.append(r.getString('auto_decision')); a.append(r.getString('auto_reason')); a.endRow()
    }
    private static ApiFailure invalidInventory() {
        new ApiFailure('SCENARIO_INVALID_INVENTORY', HttpStatus.UNPROCESSABLE_ENTITY,
            'Invalid stored paths, duplicate identifiers, invalid sizes, or malformed saved hashes prevent scenario planning.')
    }
}
