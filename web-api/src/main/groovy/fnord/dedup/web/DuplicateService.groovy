package fnord.dedup.web

import fnord.dedup.path.StoredPath
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/** Confirmed historical filesystem observations, with explicit scan scope and bounded pages. */
@Service
class DuplicateService {
    private static final Set<String> CONTENT_FIELDS = [
        'limit', 'cursor', 'scanIds', 'directory', 'name', 'path', 'extensions', 'size', 'modified', 'errorState'
    ] as Set<String>
    private static final Set<String> FIELDS = (CONTENT_FIELDS + [
        'mode', 'minOccurrences', 'minScans', 'entry', 'sort'
    ]) as Set<String>
    private static final Map<String, String> SORTS = [
        SIZE: 'size', OCCURRENCES: 'occurrences', SCANS: 'scan_count', OBSERVED_BYTES: 'observed_bytes'
    ].asImmutable()
    private static final String ERROR_SQL = '''EXISTS (SELECT 1 FROM scan_errors x
        WHERE x.scan_id=e.scan_id AND x.relative_path=e.relative_path)'''
    private final ScannerDatabase database

    DuplicateService(ScannerDatabase database) { this.database = database }

    Map groups(Map request) {
        Map spec = normalize(request)
        Map cursor = decodeCursor(spec, 'groups')
        withSelection(spec) { Connection connection, Map context ->
            Map anchor = context.reference
            Map query = context.query
            String key = SORTS[spec.sort.field]
            String direction = spec.sort.direction
            String comparison = direction == 'ASC' ? '>' : '<'
            String sql = query.sql + ' SELECT *, ' + key + ' AS sort_key FROM matches'
            List values = new ArrayList(query.values as List)
            if (cursor) {
                sql += ' WHERE (' + key + ' ' + comparison + ' CAST(? AS HUGEINT)' +
                    ' OR (' + key + '=CAST(? AS HUGEINT) AND size ' + comparison + ' ?)' +
                    ' OR (' + key + '=CAST(? AS HUGEINT) AND size=? AND sha256 ' + comparison + ' ?))'
                values.addAll([cursor.key, cursor.key, cursor.size, cursor.key, cursor.size, cursor.sha256])
            }
            sql += ' ORDER BY ' + key + ' ' + direction + ',size ' + direction +
                ',sha256 ' + direction + ' LIMIT ?'
            values.add(spec.limit + 1)
            List<Map> items = rows(connection, sql, values) { ResultSet result ->
                groupMap(result) + [_sortKey: result.getString('sort_key')]
            }
            Map page = page(spec, items) { Map last ->
                encodeCursor(spec, 'groups', [key: last._sortKey, size: last.size, sha256: last.sha256])
            }
            items.each { it.remove('_sortKey') }
            Map summary = rows(connection, query.sql + ''' SELECT count(*) AS groups,
                coalesce(sum(occurrences),0) AS occurrences,
                coalesce(sum(observed_bytes),0) AS observed_bytes FROM matches''', query.values as List) {
                ResultSet result ->
                [groups: result.getLong('groups'), occurrences: result.getString('occurrences'),
                 observedBytes: result.getString('observed_bytes')]
            }[0]
            [items: items, page: page, summary: summary,
             coverage: coverage(connection, spec), reference: anchor]
        } as Map
    }

    Map occurrences(String groupId, Map request) {
        Map identity = parseGroupId(groupId)
        Map spec = normalize(request)
        Map cursor = decodeCursor(spec, 'occurrences', groupId)
        withSelection(spec) { Connection connection, Map context ->
            Map query = context.query
            List groupValues = new ArrayList(query.values as List)
            groupValues.addAll([identity.size, identity.sha256])
            List<Map> matches = rows(connection, query.sql +
                ' SELECT * FROM matches WHERE size=? AND sha256=? LIMIT 1', groupValues) {
                ResultSet result -> groupMap(result)
            }
            if (!matches) throw new ApiFailure('DUPLICATE_GROUP_NOT_FOUND', HttpStatus.NOT_FOUND,
                'The confirmed group is no longer present in this scope and filter set.')
            Map group = matches[0]
            String sql = query.sql + ' SELECT * FROM observations WHERE size=? AND sha256=?'
            List values = new ArrayList(groupValues)
            if (cursor) {
                sql += ''' AND (scan_id>? OR (scan_id=? AND relative_path>?)
                    OR (scan_id=? AND relative_path=? AND entry_id>?))'''
                values.addAll([cursor.scanId, cursor.scanId, cursor.path,
                               cursor.scanId, cursor.path, cursor.entryId])
            }
            sql += ' ORDER BY scan_id,relative_path,entry_id LIMIT ?'
            values.add(spec.limit + 1)
            List<Map> items = rows(connection, sql, values) { ResultSet result ->
                occurrenceMap(result, group)
            }
            Map page = page(spec, items) { Map last ->
                encodeCursor(spec, 'occurrences', [scanId: last.scanId, path: last.relativePath,
                    entryId: last.entryId], groupId)
            }
            [group: group, items: items, page: page]
        } as Map
    }

    /** Shared explicit scope contract for duplicate previews and saved scenarios. */
    static Map normalize(Map request) {
        Map source = request ?: [:]
        if (!FIELDS.containsAll(source.keySet())) throw invalid('Unknown field in duplicate request.')
        Map content = FileSearchService.normalize(source.subMap(CONTENT_FIELDS - ['directory']))
        if (!content.scanIds) throw invalid('Select at least one scan explicitly.')
        content.directory = source.directory == null ? null :
            FileSearchService.normalize([directory: source.directory]).directory
        if (content.directory && !content.scanIds.contains(content.directory.scanId))
            throw invalid('The target directory must belong to a selected scan.')
        String mode = source.mode == null ? 'ANY' : source.mode
        if (!(mode in ['ANY', 'ACROSS_SCANS'])) throw invalid('Invalid duplicate mode.')
        if (mode == 'ACROSS_SCANS' && content.scanIds.size() < 2)
            throw invalid('Across-scan groups require at least two selected scans.')
        long minOccurrences = integer(source.minOccurrences, 'minOccurrences', 2, 2, Long.MAX_VALUE)
        long minScans = integer(source.minScans, 'minScans', 1, 1, 1000)
        if (mode == 'ACROSS_SCANS') minScans = Math.max(2L, minScans)
        Map entry = null
        if (source.entry != null) {
            if (!(source.entry instanceof Map) ||
                !(['scanId', 'entryId'] as Set).containsAll((source.entry as Map).keySet()))
                throw invalid('entry must contain scanId and entryId.')
            entry = [scanId: integer(source.entry.scanId, 'entry.scanId', null, 1, Long.MAX_VALUE),
                     entryId: integer(source.entry.entryId, 'entry.entryId', null, 1, Long.MAX_VALUE)]
            if (!content.scanIds.contains(entry.scanId))
                throw invalid('The reference file must belong to a selected scan.')
        }
        if (!(source.sort == null || source.sort instanceof Map)) throw invalid('Invalid sort.')
        Map sort = source.sort == null ? [:] : source.sort as Map
        if (!(['field', 'direction'] as Set).containsAll(sort.keySet())) throw invalid('Invalid sort.')
        String field = sort.field == null ? 'SIZE' : sort.field
        String direction = sort.direction == null ? 'DESC' : sort.direction
        if (!SORTS.containsKey(field) || !(direction in ['ASC', 'DESC'])) throw invalid('Invalid sort.')
        content.subMap(CONTENT_FIELDS) + [mode: mode, minOccurrences: minOccurrences,
            minScans: minScans, entry: entry, sort: [field: field, direction: direction]]
    }

    /** A single read-only, locked source snapshot. Collections are streamed by callers. */
    def withSelection(Map spec, Closure action) {
        database.withConnection { Connection connection, ignored ->
            Map anchor = validateScope(connection, spec)
            Map directory = null
            if (spec.directory) {
                List<Map> found = rows(connection, '''SELECT relative_path,kind FROM entries
                    WHERE scan_id=? AND entry_id=? LIMIT 1''',
                    [spec.directory.scanId, spec.directory.entryId]) {
                    ResultSet result -> [path: result.getString(1), kind: result.getString(2)]
                }
                if (!found) throw new ApiFailure('ENTRY_NOT_FOUND', HttpStatus.NOT_FOUND,
                    'The target directory was not found.')
                if (found[0].kind != 'DIRECTORY') throw invalid('The target entry must be a directory.')
                directory = found[0]
            }
            action.call(connection, [reference: anchor, query: groupQuery(spec, anchor, directory)])
        }
    }

    private static Map validateScope(Connection connection, Map spec) {
        SchemaInspector.inspect(connection)
        String placeholders = (['?'] * spec.scanIds.size()).join(',')
        List<Map> selected = rows(connection, '''SELECT count(*) AS scans,
            count(*) FILTER (WHERE algorithm<>'SHA-256') AS incompatible
            FROM scans WHERE scan_id IN (''' + placeholders + ')', spec.scanIds as List) {
            ResultSet result -> [scans: result.getLong('scans'), incompatible: result.getLong('incompatible')]
        }
        if (selected[0].scans != spec.scanIds.size())
            throw new ApiFailure('SCAN_NOT_FOUND', HttpStatus.NOT_FOUND, 'A selected scan was not found.')
        if (selected[0].incompatible)
            throw new ApiFailure('UNSUPPORTED_ALGORITHM', HttpStatus.UNPROCESSABLE_ENTITY,
                'Selected scans must use SHA-256.')
        if (spec.name?.operator == 'REGEX') {
            try {
                rows(connection, 'SELECT regexp_matches(\'\', ?) AS valid', [spec.name.value]) { it.getBoolean(1) }
            } catch (SQLException error) {
                throw new ApiFailure('INVALID_FILTER', HttpStatus.BAD_REQUEST,
                    'The filename regular expression is not supported.', error)
            }
        }
        if (!spec.entry) return null
        List<Map> found = rows(connection, '''SELECT e.size,e.kind,e.filename,e.relative_path,h.sha256
            FROM entries e LEFT JOIN hashes h USING (scan_id,entry_id)
            WHERE e.scan_id=? AND e.entry_id=? LIMIT 1''', [spec.entry.scanId, spec.entry.entryId]) {
            ResultSet result -> [size: result.getString('size'), kind: result.getString('kind'),
                sha256: result.getString('sha256'), filename: result.getString('filename'),
                relativePath: result.getString('relative_path')]
        }
        if (!found) throw new ApiFailure('ENTRY_NOT_FOUND', HttpStatus.NOT_FOUND,
            'The reference file was not found.')
        if (found[0].kind != 'FILE') throw invalid('The reference entry must be a regular file.')
        if (!found[0].sha256) throw new ApiFailure('HASH_UNAVAILABLE', HttpStatus.CONFLICT,
            'This file has no saved hash. Complete hashing with the CLI before finding confirmed duplicates.')
        found[0] + spec.entry
    }

    private static Map groupQuery(Map spec, Map anchor, Map directory) {
        List<String> predicates = []
        List values = []
        FileSearchService.addContentFilters(predicates, values, spec)
        if (directory) {
            predicates.add('e.scan_id=?')
            values.add(spec.directory.scanId)
            if (spec.directory.recursive) {
                predicates.add('starts_with(e.relative_path,?)')
                values.add(directory.path ? directory.path + '/' : '')
            } else {
                predicates.add('e.parent_id=?')
                values.add(spec.directory.entryId)
            }
        }
        if (spec.errorState == 'HAS') predicates.add(ERROR_SQL)
        else if (spec.errorState == 'NONE') predicates.add('NOT ' + ERROR_SQL)
        String predicate = predicates ? predicates.join(' AND ') : 'true'
        String sql = '''WITH observations AS (
            SELECT e.*,h.sha256,s.name AS scan_name,s.root AS scan_root,
            (''' + predicate + ') AS filter_match,' + ERROR_SQL + ''' AS has_error
            FROM entries e JOIN hashes h ON h.scan_id=e.scan_id AND h.entry_id=e.entry_id
            JOIN scans s ON s.scan_id=e.scan_id
            WHERE e.kind='FILE' AND e.scan_id IN (''' + (['?'] * spec.scanIds.size()).join(',') + ')'
        values.addAll(spec.scanIds)
        if (anchor) {
            sql += ' AND e.size=? AND h.sha256=?'
            values.addAll([Long.parseLong(anchor.size as String), anchor.sha256])
        }
        sql += '''), grouped AS (
            SELECT size,sha256,count(*) AS occurrences,count(DISTINCT scan_id) AS scan_count,
            count(*) FILTER (WHERE filter_match) AS matching_occurrences,
            count(*) FILTER (WHERE has_error) AS error_occurrences,
            min(relative_path) AS sample_path, size::HUGEINT*count(*) AS observed_bytes
            FROM observations GROUP BY size,sha256
        ), matches AS (SELECT * FROM grouped
            WHERE occurrences>=? AND scan_count>=? AND matching_occurrences>0)'''
        values.addAll([spec.minOccurrences, spec.minScans])
        [sql: sql, values: values]
    }

    static Map coverage(Connection connection, Map spec) {
        String placeholders = (['?'] * spec.scanIds.size()).join(',')
        Map counts = rows(connection, '''SELECT count(*) AS files,
            count(h.sha256) AS hashed_files, count(*) FILTER (WHERE h.sha256 IS NULL) AS unhashed_files
            FROM entries e LEFT JOIN hashes h USING (scan_id,entry_id)
            WHERE e.kind='FILE' AND e.scan_id IN (''' + placeholders + ')', spec.scanIds as List) {
            ResultSet result -> [files: result.getLong('files'), hashedFiles: result.getLong('hashed_files'),
                unhashedFiles: result.getLong('unhashed_files')]
        }[0]
        Map warnings = rows(connection, '''SELECT count(*) FILTER (WHERE s.phase='DISCOVERING'
            OR s.active_dir IS NOT NULL OR EXISTS(SELECT 1 FROM directories d
                WHERE d.scan_id=s.scan_id AND NOT d.completed)) AS incomplete_scans,
            (SELECT count(*) FROM scan_errors x WHERE x.scan_id IN (''' + placeholders + ''')) AS errors
            FROM scans s WHERE s.scan_id IN (''' + placeholders + ')',
            (spec.scanIds as List) + (spec.scanIds as List)) { ResultSet result ->
            [incompleteScans: result.getLong('incomplete_scans'), scanErrors: result.getLong('errors')]
        }[0]
        counts + warnings + [selectedScans: spec.scanIds.size(), persistedHashesOnly: true,
            observationsOnly: true]
    }

    private static Map groupMap(ResultSet result) {
        String size = result.getString('size')
        String sha = result.getString('sha256')
        [groupId: size + ':' + sha, size: size, sha256: sha,
         occurrences: result.getLong('occurrences'), scanCount: result.getLong('scan_count'),
         matchingOccurrences: result.getLong('matching_occurrences'),
         errorOccurrences: result.getLong('error_occurrences'), samplePath: result.getString('sample_path'),
         observedBytes: result.getString('observed_bytes')]
    }

    private static Map occurrenceMap(ResultSet result, Map group) {
        String root = result.getString('scan_root')
        String relative = result.getString('relative_path')
        [scanId: result.getLong('scan_id'), scanName: result.getString('scan_name'), scanRoot: root,
         entryId: result.getLong('entry_id'), parentId: result.getLong('parent_id'),
         filename: result.getString('filename'), relativePath: relative, path: StoredPath.join(root, relative),
         kind: 'FILE', size: group.size, sha256: group.sha256, hashState: 'HASHED',
         modifiedSec: result.getLong('modified_sec'), modifiedNano: result.getInt('modified_nano'),
         duplicateCount: group.occurrences, duplicateScanCount: group.scanCount,
         hasError: result.getBoolean('has_error'), matchesFilters: result.getBoolean('filter_match')]
    }

    private static Map parseGroupId(String value) {
        if (!value || !(value ==~ /(?:0|[1-9][0-9]{0,18}):[0-9a-f]{64}/)) throw invalid('Invalid group ID.')
        int colon = value.indexOf(':')
        [size: integer(value.substring(0, colon), 'group size', null, 0, Long.MAX_VALUE),
         sha256: value.substring(colon + 1)]
    }

    private static Map page(Map spec, List<Map> items, Closure cursor) {
        boolean more = items.size() > spec.limit
        if (more) items.remove(items.size() - 1)
        [limit: spec.limit, hasMore: more, nextCursor: more ? cursor.call(items.last()) : null]
    }

    private static String encodeCursor(Map spec, String kind, Map position, String groupId = null) {
        Map value = [version: 1, kind: kind, fingerprint: fingerprint(spec), groupId: groupId] + position
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            JsonOutput.toJson(value).getBytes(StandardCharsets.UTF_8))
    }

    private static Map decodeCursor(Map spec, String kind, String groupId = null) {
        if (!spec.cursor) return null
        try {
            Object parsed = new JsonSlurper().parseText(new String(
                Base64.getUrlDecoder().decode(spec.cursor as String), StandardCharsets.UTF_8))
            if (!(parsed instanceof Map)) throw invalid('Invalid duplicate cursor.')
            Map value = parsed as Map
            if (value.version != 1 || value.kind != kind || value.fingerprint != fingerprint(spec) ||
                value.groupId != groupId) throw invalid('Invalid or stale duplicate cursor.')
            if (kind == 'groups') {
                if (!(value.key instanceof String) || !(value.key ==~ /[0-9]{1,39}/) ||
                    new BigInteger(value.key as String) > new BigInteger('170141183460469231731687303715884105727'))
                    throw invalid('Invalid duplicate cursor.')
                Map identity = parseGroupId(value.size + ':' + value.sha256)
                return [key: value.key, size: identity.size, sha256: identity.sha256]
            }
            if (!(value.path instanceof String) || value.path.length() > 16384 || value.path.indexOf(0) >= 0)
                throw invalid('Invalid duplicate cursor.')
            [scanId: integer(value.scanId, 'cursor.scanId', null, 1, Long.MAX_VALUE), path: value.path,
             entryId: integer(value.entryId, 'cursor.entryId', null, 1, Long.MAX_VALUE)]
        } catch (ApiFailure failure) { throw failure }
        catch (Exception ignored) { throw invalid('Invalid duplicate cursor.') }
    }

    private static String fingerprint(Map spec) {
        Map stable = new LinkedHashMap(spec)
        stable.remove('limit'); stable.remove('cursor')
        HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(
            JsonOutput.toJson(stable).getBytes(StandardCharsets.UTF_8)))
    }

    private static long integer(Object value, String field, Long fallback, long min, long max) {
        if (value == null && fallback != null) return fallback
        try {
            if (!(value instanceof Number || (value instanceof String && value ==~ /[0-9]+/)))
                throw invalid('Invalid ' + field + '.')
            long parsed = new BigDecimal(value.toString()).longValueExact()
            if (parsed < min || parsed > max) throw invalid('Invalid ' + field + '.')
            return parsed
        } catch (ArithmeticException | NumberFormatException ignored) { throw invalid('Invalid ' + field + '.') }
    }

    private static List rows(Connection connection, String sql, List values, Closure mapper) {
        List output = []
        connection.prepareStatement(sql).withCloseable { statement ->
            ScenarioSql.timeout(statement)
            values.eachWithIndex { Object value, int index -> statement.setObject(index + 1, value) }
            statement.executeQuery().withCloseable { ResultSet result ->
                while (result.next()) { ScenarioSql.checkBudget(); output.add(mapper.call(result)) }
            }
        }
        output
    }

    private static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_FILTER', HttpStatus.BAD_REQUEST, message)
    }
}
