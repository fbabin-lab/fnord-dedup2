package fnord.dedup.web

import fnord.dedup.path.StoredPath
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Validated, bounded file search over historical scanner observations. */
@Service
class FileSearchService {
    private static final Set<String> TOP_FIELDS = [
        'limit', 'cursor', 'scanIds', 'directory', 'name', 'path', 'extensions',
        'size', 'modified', 'kinds', 'hashState', 'duplicate', 'errorState', 'sort'
    ] as Set<String>
    private static final Set<String> KINDS = ['FILE', 'DIRECTORY', 'SYMLINK', 'OTHER'] as Set<String>
    private static final Set<String> NAME_OPERATORS = [
        'CONTAINS', 'STARTS_WITH', 'ENDS_WITH', 'EXACT', 'GLOB', 'REGEX'
    ] as Set<String>
    private static final Set<String> HASH_STATES = ['ANY', 'HASHED', 'UNHASHED'] as Set<String>
    private static final Set<String> DUPLICATES = [
        'ANY', 'CONFIRMED', 'NOT_CONFIRMED', 'COPIES_2', 'COPIES_3', 'ACROSS_SCANS'
    ] as Set<String>
    private static final Set<String> ERROR_STATES = ['ANY', 'HAS', 'NONE'] as Set<String>
    private static final Set<String> SORT_FIELDS = ['PATH', 'NAME', 'SIZE', 'MODIFIED', 'SCAN'] as Set<String>
    private static final Set<String> DIRECTIONS = ['ASC', 'DESC'] as Set<String>
    private static final Map<String, String> SORT_SQL = [
        PATH: 'lower(e.relative_path)', NAME: 'lower(e.filename)', SIZE: 'e.size',
        MODIFIED: '(e.modified_sec::HUGEINT * 1000000000 + e.modified_nano)',
        SCAN: 'lower(s.name)'
    ].asImmutable()
    private static final Set<String> NUMERIC_SORTS = ['SIZE', 'MODIFIED'] as Set<String>

    private final ScannerDatabase database

    FileSearchService(ScannerDatabase database) { this.database = database }

    Map search(Map request) {
        Map spec = normalize(request)
        Map cursor = decodeCursor(spec.cursor as String, spec)
        database.withConnection { Connection connection, ignored ->
            SchemaInspector.inspect(connection)
            Map directory = validateScope(connection, spec)
            SearchQuery query = buildQuery(spec, directory, cursor)
            List<Map> found
            try {
                found = rows(connection, query.sql, query.values) { ResultSet result ->
                    resultMap(result)
                }
            } catch (SQLException error) {
                if ((spec.name as Map)?.operator in ['REGEX', 'GLOB']) {
                    throw new ApiFailure('INVALID_FILTER', HttpStatus.BAD_REQUEST,
                        'The filename pattern is not supported.', error)
                }
                throw error
            }
            int limit = spec.limit as int
            boolean hasMore = found.size() > limit
            if (hasMore) found.remove(found.size() - 1)
            Map last = found ? found.last() : null
            String nextCursor = hasMore ? encodeCursor(spec, last) : null
            found.each { it.remove('_sortKey') }
            boolean hasUnhashed = scopeHasUnhashedFiles(connection, spec, directory)
            [items: found,
             page: [limit: limit, hasMore: hasMore,
                    nextCursor: nextCursor],
             coverage: [persistedHashesOnly: true, hasUnhashedFiles: hasUnhashed]]
        } as Map
    }

    /** Canonical request used by saved searches. Cursors are intentionally transient. */
    static Map normalizedSavedRequest(Object request) {
        if (!(request instanceof Map)) throw invalid('Saved search request must be an object.')
        Map value = new LinkedHashMap(normalize(request as Map))
        value.remove('cursor')
        value
    }

    static Map normalize(Map request) {
        Map source = request ?: [:]
        assertKeys(source, TOP_FIELDS, 'search request')
        int limit = boundedInt(source.limit, 'limit', 100, 1, 500)
        String cursor = optionalText(source.cursor, 'cursor', 65536, false)

        List<Long> scanIds = idList(source.scanIds)
        Map directory = normalizeDirectory(source.directory)
        if (directory) {
            long directoryScan = directory.scanId as long
            if (scanIds && !scanIds.contains(directoryScan))
                throw invalid('Directory scope must belong to the selected scan.')
            scanIds = [directoryScan]
        }

        Map name = normalizeName(source.name)
        Map path = normalizePath(source.path)
        List<String> extensions = stringList(source.extensions, 'extensions', 100, 64, false)
            .collect { normalizeExtension(it) }.unique().sort()
        Map size = normalizeRange(source.size, 'size', true)
        Map modified = normalizeRange(source.modified, 'modified', false)
        List<String> kinds = enumList(source.kinds, 'kinds', KINDS, ['FILE'])
        String hashState = enumValue(source.hashState, 'hashState', HASH_STATES, 'ANY')
        String duplicate = enumValue(source.duplicate, 'duplicate', DUPLICATES, 'ANY')
        String errorState = enumValue(source.errorState, 'errorState', ERROR_STATES, 'ANY')
        Map sort = normalizeSort(source.sort)

        [limit: limit, cursor: cursor, scanIds: scanIds, directory: directory, name: name,
         path: path, extensions: extensions, size: size, modified: modified, kinds: kinds,
         hashState: hashState, duplicate: duplicate, errorState: errorState, sort: sort]
    }

    private static Map validateScope(Connection connection, Map spec) {
        List<Long> ids = spec.scanIds as List<Long>
        if (ids) {
            String placeholders = (['?'] * ids.size()).join(',')
            long found = scalar(connection,
                'SELECT count(*) FROM scans WHERE scan_id IN (' + placeholders + ')', ids)
            if (found != ids.size()) throw new ApiFailure('SCAN_NOT_FOUND', HttpStatus.NOT_FOUND,
                'One or more selected scans were not found.')
        }
        Map scope = spec.directory as Map
        if (!scope) return null
        List<Map> matches = rows(connection, '''SELECT relative_path,kind FROM entries
            WHERE scan_id=? AND entry_id=? LIMIT 1''', [scope.scanId, scope.entryId]) { ResultSet result ->
            [relativePath: result.getString('relative_path'), kind: result.getString('kind')]
        }
        if (!matches) throw new ApiFailure('ENTRY_NOT_FOUND', HttpStatus.NOT_FOUND,
            'The search directory was not found in this scan.')
        if (matches[0].kind != 'DIRECTORY') throw invalid('Directory scope must identify a directory.')
        matches[0]
    }

    private static SearchQuery buildQuery(Map spec, Map directory, Map cursor) {
        List duplicateValues = []
        String duplicateScope = ''
        List<Long> scanIds = spec.scanIds as List<Long>
        if (scanIds) {
            duplicateScope = ' AND de.scan_id IN (' + (['?'] * scanIds.size()).join(',') + ')'
            duplicateValues.addAll(scanIds)
        }

        List<String> clauses = []
        List values = []
        addScope(clauses, values, 'e', spec, directory)

        List<String> kinds = spec.kinds as List<String>
        clauses.add('e.kind IN (' + (['?'] * kinds.size()).join(',') + ')')
        values.addAll(kinds)

        addContentFilters(clauses, values, spec)
        switch (spec.hashState) {
            case 'HASHED': clauses.add('h.sha256 IS NOT NULL'); break
            case 'UNHASHED': clauses.add('h.sha256 IS NULL'); break
        }
        switch (spec.duplicate) {
            case 'CONFIRMED':
            case 'COPIES_2': clauses.add('coalesce(ds.copies,0)>=2'); break
            case 'COPIES_3': clauses.add('coalesce(ds.copies,0)>=3'); break
            case 'ACROSS_SCANS': clauses.add('coalesce(ds.scan_copies,0)>=2'); break
            case 'NOT_CONFIRMED': clauses.add('coalesce(ds.copies,0)<2'); break
        }
        String errorSql = '''EXISTS (SELECT 1 FROM scan_errors x
            WHERE x.scan_id=e.scan_id AND x.relative_path=e.relative_path)'''
        if (spec.errorState == 'HAS') clauses.add(errorSql)
        else if (spec.errorState == 'NONE') clauses.add('NOT ' + errorSql)

        String sortField = spec.sort.field
        String direction = spec.sort.direction
        String sortExpression = SORT_SQL[sortField]
        String sql = '''WITH duplicate_stats AS (
              SELECT de.size,dh.sha256,count(*) AS copies,
                     count(DISTINCT de.scan_id) AS scan_copies
              FROM entries de JOIN hashes dh USING (scan_id,entry_id)
              WHERE de.kind='FILE' ''' + duplicateScope + '''
              GROUP BY de.size,dh.sha256
            ), matches AS (
              SELECT e.scan_id,e.entry_id,e.parent_id,e.relative_path,e.filename,e.kind,
                     e.size,e.modified_sec,e.modified_nano,h.sha256,s.name AS scan_name,
                     s.root AS scan_root,ds.copies AS duplicate_count,
                     ds.scan_copies AS duplicate_scan_count,
                     ''' + errorSql + ''' AS has_error,
                     ''' + sortExpression + ''' AS sort_key
              FROM entries e JOIN scans s ON s.scan_id=e.scan_id
              LEFT JOIN hashes h ON h.scan_id=e.scan_id AND h.entry_id=e.entry_id
              LEFT JOIN duplicate_stats ds ON ds.size=e.size AND ds.sha256=h.sha256
              WHERE ''' + clauses.join(' AND ') + '''
            ) SELECT * FROM matches'''
        List allValues = []
        allValues.addAll(duplicateValues)
        allValues.addAll(values)
        if (cursor) {
            String comparator = direction == 'ASC' ? '>' : '<'
            String keyParameter = NUMERIC_SORTS.contains(sortField) ? 'CAST(? AS HUGEINT)' : '?'
            sql += ''' WHERE (sort_key ''' + comparator + ' ' + keyParameter +
                ''' OR (sort_key=''' + keyParameter + ''' AND scan_id ''' + comparator + ''' ?)
                OR (sort_key=''' + keyParameter + ''' AND scan_id=? AND entry_id ''' + comparator + ''' ?))'''
            allValues.addAll([cursor.key, cursor.key, cursor.scanId,
                              cursor.key, cursor.scanId, cursor.entryId])
        }
        sql += ' ORDER BY sort_key ' + direction + ',scan_id ' + direction +
            ',entry_id ' + direction + ' LIMIT ?'
        allValues.add((spec.limit as int) + 1)
        new SearchQuery(sql: sql, values: allValues)
    }

    private static void addScope(List<String> clauses, List values, String alias,
                                 Map spec, Map directory) {
        List<Long> ids = spec.scanIds as List<Long>
        if (ids) {
            clauses.add(alias + '.scan_id IN (' + (['?'] * ids.size()).join(',') + ')')
            values.addAll(ids)
        }
        Map requested = spec.directory as Map
        if (!requested) return
        if (requested.recursive) {
            String prefix = directory.relativePath ? directory.relativePath + '/' : ''
            clauses.add('starts_with(' + alias + '.relative_path, ?)')
            values.add(prefix)
            clauses.add(alias + '.entry_id<>?')
            values.add(requested.entryId)
        } else {
            clauses.add(alias + '.parent_id=?')
            values.add(requested.entryId)
        }
    }

    /** Shared predicates over the e entry alias; input must already be normalized. */
    static void addContentFilters(List<String> clauses, List values, Map spec) {
        Map name = spec.name as Map
        if (name) addNameFilter(clauses, values, name)
        Map path = spec.path as Map
        if (path.contains) { clauses.add('contains(e.relative_path, ?)'); values.add(path.contains) }
        if (path.startsWith) { clauses.add('starts_with(e.relative_path, ?)'); values.add(path.startsWith) }
        if (path.under) {
            clauses.add('(e.relative_path=? OR starts_with(e.relative_path, ?))')
            values.add(path.under); values.add(path.under + '/')
        }
        (path.excludes as List<String>).each { String excluded ->
            clauses.add('NOT (e.relative_path=? OR starts_with(e.relative_path, ?))')
            values.add(excluded); values.add(excluded + '/')
        }
        List<String> extensions = spec.extensions as List<String>
        if (extensions) {
            clauses.add('(' + (['ends_with(lower(e.filename), ?)'] * extensions.size()).join(' OR ') + ')')
            values.addAll(extensions.collect { '.' + it.toLowerCase(Locale.ROOT) })
        }
        Map size = spec.size as Map
        if (size.exact != null) { clauses.add('e.size=?'); values.add(size.exact) }
        else {
            if (size.min != null) { clauses.add('e.size>=?'); values.add(size.min) }
            if (size.max != null) { clauses.add('e.size<=?'); values.add(size.max) }
        }
        Map modified = spec.modified as Map
        if (modified.exact != null) { clauses.add('e.modified_sec=?'); values.add(modified.exact) }
        else {
            if (modified.min != null) { clauses.add('e.modified_sec>=?'); values.add(modified.min) }
            if (modified.max != null) { clauses.add('e.modified_sec<=?'); values.add(modified.max) }
        }
    }

    private static void addNameFilter(List<String> clauses, List values, Map name) {
        String column = name.caseSensitive ? 'e.filename' : 'lower(e.filename)'
        String parameter = name.caseSensitive ? '?' : 'lower(?)'
        switch (name.operator) {
            case 'CONTAINS': clauses.add('contains(' + column + ', ' + parameter + ')'); values.add(name.value); break
            case 'STARTS_WITH': clauses.add('starts_with(' + column + ', ' + parameter + ')'); values.add(name.value); break
            case 'ENDS_WITH': clauses.add('ends_with(' + column + ', ' + parameter + ')'); values.add(name.value); break
            case 'EXACT': clauses.add(column + '=' + parameter); values.add(name.value); break
            case 'REGEX':
                clauses.add("regexp_matches(e.filename, ?, '${name.caseSensitive ? 'c' : 'i'}')")
                values.add(name.value)
                break
            case 'GLOB':
                clauses.add("regexp_matches(e.filename, ?, '${name.caseSensitive ? 'c' : 'i'}')")
                values.add(globRegex(name.value as String))
                break
        }
    }

    private static boolean scopeHasUnhashedFiles(Connection connection, Map spec, Map directory) {
        List<String> clauses = ["ue.kind='FILE'", 'uh.sha256 IS NULL']
        List values = []
        addScope(clauses, values, 'ue', spec, directory)
        one(connection, '''SELECT EXISTS(SELECT 1 FROM entries ue
            LEFT JOIN hashes uh USING (scan_id,entry_id) WHERE ''' + clauses.join(' AND ') + ')',
            values) { ResultSet result -> result.getBoolean(1) } as boolean
    }

    private static Map resultMap(ResultSet result) {
        String root = result.getString('scan_root')
        String relative = result.getString('relative_path')
        String sha = result.getString('sha256')
        [scanId: result.getLong('scan_id'), scanName: result.getString('scan_name'), scanRoot: root,
         entryId: result.getLong('entry_id'), parentId: result.getLong('parent_id'),
         relativePath: relative, path: StoredPath.join(root, relative),
         filename: result.getString('filename'), kind: result.getString('kind'),
         size: String.valueOf(result.getLong('size')), modifiedSec: result.getLong('modified_sec'),
         modifiedNano: result.getInt('modified_nano'), sha256: sha,
         hashState: sha ? 'HASHED' : 'UNHASHED',
         duplicateCount: sha ? result.getLong('duplicate_count') : null,
         duplicateScanCount: sha ? result.getLong('duplicate_scan_count') : null,
         hasError: result.getBoolean('has_error'),
         _sortKey: result.getObject('sort_key').toString()]
    }

    private static String encodeCursor(Map spec, Map last) {
        Map value = [fingerprint: fingerprint(spec), field: spec.sort.field,
                     direction: spec.sort.direction, key: last._sortKey,
                     scanId: last.scanId, entryId: last.entryId]
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            JsonOutput.toJson(value).getBytes(StandardCharsets.UTF_8))
    }

    private static Map decodeCursor(String token, Map spec) {
        if (!token) return null
        try {
            Map value = new JsonSlurper().parseText(new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)) as Map
            if (value.key == null || !(value.key instanceof String) || value.key.length() > 16384 ||
                !(value.scanId instanceof Number) || value.scanId.longValue() < 1 ||
                !(value.entryId instanceof Number) || value.entryId.longValue() < 1 ||
                value.field != spec.sort.field || value.direction != spec.sort.direction ||
                value.fingerprint != fingerprint(spec)) throw invalid('Invalid or stale search cursor.')
            [key: value.key, scanId: value.scanId.longValue(), entryId: value.entryId.longValue()]
        } catch (ApiFailure failure) { throw failure }
        catch (Exception ignored) { throw invalid('Invalid or stale search cursor.') }
    }

    private static String fingerprint(Map spec) {
        Map stable = new LinkedHashMap(spec)
        stable.remove('limit'); stable.remove('cursor')
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(
            JsonOutput.toJson(stable).getBytes(StandardCharsets.UTF_8))
        HexFormat.of().formatHex(digest)
    }

    private static Map normalizeDirectory(Object value) {
        if (value == null) return null
        if (!(value instanceof Map)) throw invalid('directory must be an object.')
        Map source = value as Map
        assertKeys(source, ['scanId', 'entryId', 'recursive'] as Set<String>, 'directory')
        [scanId: positiveLong(source.scanId, 'directory.scanId'),
         entryId: positiveLong(source.entryId, 'directory.entryId'),
         recursive: booleanValue(source.recursive, 'directory.recursive', true)]
    }

    private static Map normalizeName(Object value) {
        if (value == null) return null
        if (!(value instanceof Map)) throw invalid('name must be an object.')
        Map source = value as Map
        assertKeys(source, ['operator', 'value', 'caseSensitive'] as Set<String>, 'name')
        String text = requiredText(source.value, 'name.value', 1000)
        String operator = enumValue(source.operator, 'name.operator', NAME_OPERATORS, 'CONTAINS')
        if (operator == 'REGEX') {
            try { Pattern.compile(text) }
            catch (PatternSyntaxException ignored) { throw invalid('Invalid filename regular expression.') }
        }
        [operator: operator, value: text,
         caseSensitive: booleanValue(source.caseSensitive, 'name.caseSensitive', false)]
    }

    private static Map normalizePath(Object value) {
        if (value == null) return [contains: null, startsWith: null, under: null, excludes: []]
        if (!(value instanceof Map)) throw invalid('path must be an object.')
        Map source = value as Map
        assertKeys(source, ['contains', 'startsWith', 'under', 'excludes'] as Set<String>, 'path')
        [contains: optionalText(source.contains, 'path.contains', 4096, false),
         startsWith: optionalText(source.startsWith, 'path.startsWith', 4096, false),
         under: optionalText(source.under, 'path.under', 4096, false),
         excludes: stringList(source.excludes, 'path.excludes', 50, 4096, false).unique()]
    }

    private static Map normalizeRange(Object value, String field, boolean nonnegative) {
        if (value == null) return [exact: null, min: null, max: null]
        if (!(value instanceof Map)) throw invalid(field + ' must be an object.')
        Map source = value as Map
        assertKeys(source, ['exact', 'min', 'max'] as Set<String>, field)
        Long exact = optionalLong(source.exact, field + '.exact', nonnegative)
        Long min = optionalLong(source.min, field + '.min', nonnegative)
        Long max = optionalLong(source.max, field + '.max', nonnegative)
        if (exact != null && (min != null || max != null))
            throw invalid(field + '.exact cannot be combined with min or max.')
        if (min != null && max != null && min > max) throw invalid(field + ' minimum exceeds maximum.')
        [exact: exact, min: min, max: max]
    }

    private static Map normalizeSort(Object value) {
        if (value == null) return [field: 'PATH', direction: 'ASC']
        if (!(value instanceof Map)) throw invalid('sort must be an object.')
        Map source = value as Map
        assertKeys(source, ['field', 'direction'] as Set<String>, 'sort')
        [field: enumValue(source.field, 'sort.field', SORT_FIELDS, 'PATH'),
         direction: enumValue(source.direction, 'sort.direction', DIRECTIONS, 'ASC')]
    }

    private static List<Long> idList(Object value) {
        if (value == null) return []
        if (!(value instanceof Collection)) throw invalid('scanIds must be an array.')
        if ((value as Collection).size() > 1000) throw invalid('Too many selected scans.')
        (value as Collection).collect { positiveLong(it, 'scanIds') }.unique().sort()
    }

    private static List<String> enumList(Object value, String field, Set<String> allowed,
                                         List<String> fallback) {
        if (value == null) return fallback
        if (!(value instanceof Collection)) throw invalid(field + ' must be an array.')
        if ((value as Collection).empty) throw invalid(field + ' cannot be empty.')
        if ((value as Collection).size() > allowed.size()) throw invalid('Too many ' + field + ' values.')
        (value as Collection).collect { enumValue(it, field, allowed, null) }.unique().sort()
    }

    private static List<String> stringList(Object value, String field, int maxItems,
                                           int maxLength, boolean trim) {
        if (value == null) return []
        if (!(value instanceof Collection)) throw invalid(field + ' must be an array.')
        if ((value as Collection).size() > maxItems) throw invalid('Too many ' + field + ' values.')
        (value as Collection).collect { Object item ->
            if (!(item instanceof String)) throw invalid(field + ' values must be strings.')
            String text = trim ? (item as String).trim() : item as String
            if (!text || text.length() > maxLength || text.indexOf(0) >= 0)
                throw invalid('Invalid ' + field + ' value.')
            text
        }
    }

    private static String normalizeExtension(String value) {
        String result = value.startsWith('.') ? value.substring(1) : value
        if (!result || result.contains('/') || result.contains('\\') || result.indexOf(0) >= 0)
            throw invalid('Invalid extension.')
        result
    }

    private static String globRegex(String glob) {
        StringBuilder result = new StringBuilder('^')
        glob.toCharArray().each { char ch ->
            if (ch == '*') result.append('.*')
            else if (ch == '?') result.append('.')
            else {
                if ('\\.^$|()[]{}+'.indexOf(ch as int) >= 0) result.append('\\')
                result.append(ch)
            }
        }
        result.append('$').toString()
    }

    private static String enumValue(Object value, String field, Set<String> allowed, String fallback) {
        if (value == null && fallback != null) return fallback
        if (!(value instanceof String) || !allowed.contains(value))
            throw invalid('Invalid ' + field + '.')
        value as String
    }

    private static boolean booleanValue(Object value, String field, boolean fallback) {
        if (value == null) return fallback
        if (!(value instanceof Boolean)) throw invalid(field + ' must be true or false.')
        value as boolean
    }

    private static int boundedInt(Object value, String field, int fallback, int min, int max) {
        if (value == null) return fallback
        long parsed = requiredLong(value, field)
        if (parsed < min || parsed > max) throw invalid(field + ' is out of range.')
        parsed as int
    }

    private static long positiveLong(Object value, String field) {
        long result = requiredLong(value, field)
        if (result < 1) throw invalid('Invalid ' + field + '.')
        result
    }

    private static Long optionalLong(Object value, String field, boolean nonnegative) {
        if (value == null || value == '') return null
        long result = requiredLong(value, field)
        if (nonnegative && result < 0) throw invalid(field + ' cannot be negative.')
        result
    }

    private static long requiredLong(Object value, String field) {
        try {
            if (value instanceof BigDecimal) return (value as BigDecimal).longValueExact()
            if (value instanceof BigInteger) return (value as BigInteger).longValueExact()
            if (value instanceof Number) {
                String rendered = value.toString()
                if (rendered.contains('.') || rendered.contains('e') || rendered.contains('E'))
                    return new BigDecimal(rendered).longValueExact()
                return Long.parseLong(rendered)
            }
            if (value instanceof String && value ==~ /-?[0-9]+/) return Long.parseLong(value)
        } catch (ArithmeticException | NumberFormatException ignored) { }
        throw invalid('Invalid ' + field + '.')
    }

    private static String requiredText(Object value, String field, int maxLength) {
        String result = optionalText(value, field, maxLength, false)
        if (!result) throw invalid(field + ' is required.')
        result
    }

    private static String optionalText(Object value, String field, int maxLength, boolean trim) {
        if (value == null || value == '') return null
        if (!(value instanceof String)) throw invalid(field + ' must be a string.')
        String result = trim ? (value as String).trim() : value as String
        if (!result || result.length() > maxLength || result.indexOf(0) >= 0)
            throw invalid('Invalid ' + field + '.')
        result
    }

    private static void assertKeys(Map value, Set<String> allowed, String field) {
        if (!allowed.containsAll(value.keySet())) throw invalid('Unknown field in ' + field + '.')
    }

    private static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_FILTER', HttpStatus.BAD_REQUEST, message)
    }

    private static long scalar(Connection connection, String sql, List values) {
        one(connection, sql, values) { ResultSet result -> result.getLong(1) } as long
    }

    private static def one(Connection connection, String sql, List values, Closure mapper) {
        List found = rows(connection, sql, values, mapper)
        found ? found[0] : null
    }

    private static List rows(Connection connection, String sql, List values, Closure mapper) {
        List output = []
        connection.prepareStatement(sql).withCloseable { PreparedStatement statement ->
            values.eachWithIndex { value, index -> statement.setObject(index + 1, value) }
            statement.executeQuery().withCloseable { ResultSet result ->
                while (result.next()) output.add(mapper.call(result))
            }
        }
        output
    }

    private static class SearchQuery {
        String sql
        List values
    }
}
