package fnord.dedup.web

import fnord.dedup.path.StoredPath
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Bounded read-only queries against historical scanner observations. */
@Service
class InventoryService {
    private final ScannerDatabase database

    InventoryService(ScannerDatabase database) { this.database = database }

    Map dashboard() {
        read { Connection c, Map schema ->
            Map inventory = one(c, '''SELECT count(*) AS entries,
                count(*) FILTER (WHERE kind='FILE') AS files,
                count(*) FILTER (WHERE kind='DIRECTORY') AS directories,
                coalesce(sum(size::HUGEINT) FILTER (WHERE kind='FILE'),0) AS file_bytes
                FROM entries''', []) { ResultSet r ->
                [entries: r.getLong('entries'), files: r.getLong('files'),
                 directories: r.getLong('directories'), fileBytes: r.getObject('file_bytes').toString()]
            }
            long groups = scalar(c, '''SELECT count(*) FROM
                (SELECT e.size,h.sha256 FROM entries e JOIN hashes h USING (scan_id,entry_id)
                 WHERE e.kind='FILE' GROUP BY e.size,h.sha256 HAVING count(*)>1)''', [])
            [scans: scalar(c, 'SELECT count(*) FROM scans', []),
             inventory: inventory,
             hashesCompleted: scalar(c, 'SELECT count(*) FROM hashes', []),
             confirmedDuplicateGroups: groups,
             scanErrors: scalar(c, 'SELECT count(*) FROM scan_errors', []),
             archiveRuns: schema.archiveSchema == null ? null : scalar(c, 'SELECT count(*) FROM archive_runs', []),
             archiveErrors: schema.archiveSchema == null ? null : scalar(c, 'SELECT count(*) FROM archive_errors', []),
             imageRuns: schema.imageSchema == null ? null : scalar(c, 'SELECT count(*) FROM image_runs', []),
             imageErrors: schema.imageSchema == null ? null : scalar(c, 'SELECT count(*) FROM image_errors', [])]
        }
    }

    Map scans(String limitText, String afterText, String name, String phase, String hasErrorsText) {
        int limit = pageSize(limitText)
        long after = optionalLong(afterText, 'after', 0L)
        if (name?.length() > 256 || phase?.length() > 64) throw invalid('Scan filter is too long.')
        if (hasErrorsText && !(hasErrorsText in ['true', 'false'])) throw invalid('hasErrors must be true or false.')
        read { Connection c, Map ignored ->
            StringBuilder sql = new StringBuilder('''SELECT scan_id,name,root,phase,algorithm,created_at,updated_at
                FROM scans s WHERE scan_id>?''')
            List values = [after]
            if (name) { sql.append(' AND contains(lower(name),lower(?))'); values.add(name) }
            if (phase) { sql.append(' AND phase=?'); values.add(phase) }
            if (hasErrorsText) {
                sql.append(hasErrorsText == 'true' ?
                    ' AND EXISTS (SELECT 1 FROM scan_errors x WHERE x.scan_id=s.scan_id)' :
                    ' AND NOT EXISTS (SELECT 1 FROM scan_errors x WHERE x.scan_id=s.scan_id)')
            }
            sql.append(' ORDER BY scan_id LIMIT ?')
            values.add(limit + 1)
            List<Map> found = rows(c, sql.toString(), values) { ResultSet r -> scanMap(r) }
            boolean hasMore = found.size() > limit
            if (hasMore) found.remove(found.size() - 1)
            Map<Long, Map> stats = summaries(c, found.collect { it.scanId as long })
            found.each { it.putAll(stats[it.scanId as long] ?: emptySummary()) }
            [items: found, page: [limit: limit,
                nextCursor: hasMore ? found.last().scanId.toString() : null, hasMore: hasMore]]
        }
    }

    Map scan(long scanId) {
        read { Connection c, Map ignored ->
            Map result = requiredScan(c, scanId)
            result.putAll(summaries(c, [scanId])[scanId] ?: emptySummary())
            result.pendingDirectories = scalar(c,
                'SELECT count(*) FROM directories WHERE scan_id=? AND NOT completed', [scanId])
            result.confirmedDuplicateGroups = scalar(c, '''SELECT count(*) FROM
                (SELECT e.size,h.sha256 FROM entries e JOIN hashes h USING (scan_id,entry_id)
                 WHERE e.scan_id=? AND e.kind='FILE'
                 GROUP BY e.size,h.sha256 HAVING count(*)>1)''', [scanId])
            result
        }
    }

    Map entry(long scanId, long entryId) {
        read { Connection c, Map ignored ->
            Map scan = requiredScan(c, scanId)
            Map result = requiredEntry(c, scan, entryId)
            result.scanId = scanId
            result.scanName = scan.name
            result.scanRoot = scan.root
            if (result.kind == 'FILE' && result.sha256 && scan.algorithm == 'SHA-256') {
                result.duplicateCount = scalar(c, '''SELECT count(*) FROM entries e
                    JOIN hashes h USING (scan_id,entry_id)
                    JOIN scans s USING (scan_id)
                    WHERE e.kind='FILE' AND s.algorithm='SHA-256' AND e.size=? AND h.sha256=?''',
                    [Long.parseLong(result.size as String), result.sha256])
            } else result.duplicateCount = null
            result.relatedErrors = rows(c, '''SELECT phase,message,recorded_at_ms
                FROM scan_errors WHERE scan_id=? AND relative_path=?
                ORDER BY recorded_at_ms DESC LIMIT 20''',
                [scanId, result.relativePath]) { ResultSet r ->
                [phase: r.getString('phase'), message: r.getString('message'),
                 recordedAtMs: r.getLong('recorded_at_ms')]
            }
            result
        }
    }

    Map occurrences(long scanId, long entryId, String limitText, String cursorText) {
        int limit = boundedInt(limitText, 'limit', 20, 500, 1)
        read { Connection c, Map ignored ->
            Map scan = requiredScan(c, scanId)
            Map reference = requiredEntry(c, scan, entryId)
            if (reference.kind != 'FILE') throw invalid('Occurrences are available only for a regular file.')
            if (scan.algorithm != 'SHA-256')
                throw new ApiFailure('UNSUPPORTED_ALGORITHM', HttpStatus.UNPROCESSABLE_ENTITY,
                    'Confirmed occurrences require a SHA-256 scan.')
            if (!reference.sha256)
                throw new ApiFailure('HASH_UNAVAILABLE', HttpStatus.CONFLICT,
                    'This file has no saved hash. Complete hashing with the CLI before finding confirmed occurrences.')
            Map binding = [endpoint: 'entry-occurrences', referenceScanId: String.valueOf(scanId),
                referenceEntryId: String.valueOf(entryId), size: reference.size, sha256: reference.sha256]
            Map cursor = decodeOccurrenceCursor(cursorText, binding)
            String sql = '''SELECT e.*,h.sha256,s.name AS scan_name,s.root AS scan_root
                FROM entries e JOIN hashes h USING (scan_id,entry_id)
                JOIN scans s USING (scan_id)
                WHERE e.kind='FILE' AND s.algorithm='SHA-256' AND e.size=? AND h.sha256=?'''
            List values = [Long.parseLong(reference.size as String), reference.sha256]
            if (cursor) {
                sql += ''' AND (e.scan_id>? OR (e.scan_id=? AND
                    (e.relative_path>? OR (e.relative_path=? AND e.entry_id>?))))'''
                values.addAll([cursor.scanId, cursor.scanId, cursor.relativePath,
                    cursor.relativePath, cursor.entryId])
            }
            sql += ' ORDER BY e.scan_id,e.relative_path,e.entry_id LIMIT ?'
            values.add(limit + 1)
            List<Map> found = rows(c, sql, values) { ResultSet r ->
                entryMap(r, r.getString('scan_root')) + [scanId: r.getLong('scan_id'),
                    scanName: r.getString('scan_name'), scanRoot: r.getString('scan_root')]
            }
            boolean hasMore = found.size() > limit
            if (hasMore) found.remove(found.size() - 1)
            [items: found, page: [limit: limit, hasMore: hasMore,
                nextCursor: hasMore ? encodeOccurrenceCursor(binding, found.last()) : null]]
        }
    }

    Map children(long scanId, long entryId, String limitText, String cursorText) {
        int limit = pageSize(limitText)
        Map cursor = decodeCursor(cursorText, scanId, entryId)
        read { Connection c, Map ignored ->
            Map scan = requiredScan(c, scanId)
            Map directory = requiredEntry(c, scan, entryId)
            if (directory.kind != 'DIRECTORY') throw invalid('Children are available only for a directory.')
            StringBuilder sql = new StringBuilder('''WITH children AS (
                SELECT e.entry_id,e.parent_id,e.relative_path,e.filename,e.kind,e.size,
                    e.modified_sec,e.modified_nano,h.sha256,
                    CASE WHEN e.kind='DIRECTORY' THEN 0 ELSE 1 END AS rank
                FROM entries e LEFT JOIN hashes h USING (scan_id,entry_id)
                WHERE e.scan_id=? AND e.parent_id=?
            ) SELECT * FROM children WHERE 1=1''')
            List values = [scanId, entryId]
            if (cursor) {
                sql.append(''' AND (rank>? OR (rank=? AND filename>?)
                    OR (rank=? AND filename=? AND entry_id>?))''')
                values.addAll([cursor.rank, cursor.rank, cursor.name,
                               cursor.rank, cursor.name, cursor.entryId])
            }
            sql.append(' ORDER BY rank,filename,entry_id LIMIT ?')
            values.add(limit + 1)
            List<Map> found = rows(c, sql.toString(), values) { ResultSet r -> entryMap(r, scan.root as String) }
            boolean hasMore = found.size() > limit
            if (hasMore) found.remove(found.size() - 1)
            Map last = found ? found.last() : null
            [directory: directory, items: found, page: [limit: limit, hasMore: hasMore,
                nextCursor: hasMore ? encodeCursor(scanId, entryId, last) : null]]
        }
    }

    List<Map> breadcrumbs(long scanId, long entryId) {
        read { Connection c, Map ignored ->
            Map scan = requiredScan(c, scanId)
            List<Map> path = []
            Set<Long> visited = [] as Set<Long>
            long current = entryId
            while (current != 0L) {
                if (path.size() >= 1024 || !visited.add(current))
                    throw new ApiFailure('INVALID_INVENTORY', HttpStatus.UNPROCESSABLE_ENTITY,
                        'Directory ancestry is too deep or contains a cycle.')
                Map part = requiredEntry(c, scan, current)
                path.add([entryId: part.entryId, parentId: part.parentId,
                          name: part.filename, path: part.path, kind: part.kind])
                current = part.parentId as long
            }
            path.reverse()
        }
    }

    Map errors(long scanId, String limitText, String offsetText) {
        int limit = pageSize(limitText)
        int offset = boundedInt(offsetText, 'offset', 0, 1_000_000)
        read { Connection c, Map ignored ->
            requiredScan(c, scanId)
            List<Map> found = rows(c, '''SELECT phase,relative_path,message,recorded_at_ms
                FROM scan_errors WHERE scan_id=?
                ORDER BY recorded_at_ms DESC,phase,relative_path,message
                LIMIT ? OFFSET ?''', [scanId, limit + 1, offset]) { ResultSet r ->
                [phase: r.getString('phase'), relativePath: r.getString('relative_path'),
                 message: r.getString('message'), recordedAtMs: r.getLong('recorded_at_ms')]
            }
            boolean hasMore = found.size() > limit
            if (hasMore) found.remove(found.size() - 1)
            [items: found, page: [limit: limit, hasMore: hasMore,
                nextCursor: hasMore ? String.valueOf(offset + limit) : null]]
        }
    }

    private def read(Closure action) {
        database.withConnection { Connection c, ignored ->
            Map schema = SchemaInspector.inspect(c)
            action.call(c, schema)
        }
    }

    private static Map requiredScan(Connection c, long id) {
        Map found = one(c, '''SELECT scan_id,name,root,phase,algorithm,created_at,updated_at
            FROM scans WHERE scan_id=? LIMIT 1''', [id]) { ResultSet r -> scanMap(r) }
        if (!found) throw new ApiFailure('SCAN_NOT_FOUND', HttpStatus.NOT_FOUND, 'Scan not found.')
        found
    }

    private static Map requiredEntry(Connection c, Map scan, long id) {
        Map found = one(c, '''SELECT e.entry_id,e.parent_id,e.relative_path,e.filename,e.kind,
            e.size,e.modified_sec,e.modified_nano,h.sha256
            FROM entries e LEFT JOIN hashes h USING (scan_id,entry_id)
            WHERE e.scan_id=? AND e.entry_id=? LIMIT 1''', [scan.scanId, id]) { ResultSet r ->
            entryMap(r, scan.root as String)
        }
        if (!found) throw new ApiFailure('ENTRY_NOT_FOUND', HttpStatus.NOT_FOUND, 'Entry not found in this scan.')
        found
    }

    private static Map<Long, Map> summaries(Connection c, List<Long> ids) {
        if (!ids) return [:]
        String placeholders = (['?'] * ids.size()).join(',')
        Map<Long, Map> result = [:]
        rows(c, '''SELECT scan_id,
            count(*) FILTER (WHERE kind='FILE') AS files,
            count(*) FILTER (WHERE kind='DIRECTORY') AS directories,
            count(*) FILTER (WHERE kind='SYMLINK') AS symlinks,
            count(*) FILTER (WHERE kind='OTHER') AS other_entries,
            coalesce(sum(size::HUGEINT) FILTER (WHERE kind='FILE'),0) AS file_bytes
            FROM entries WHERE scan_id IN (''' + placeholders + ') GROUP BY scan_id', ids) { ResultSet r ->
            long id = r.getLong('scan_id')
            result[id] = [files: r.getLong('files'), directories: r.getLong('directories'),
                          symlinks: r.getLong('symlinks'), otherEntries: r.getLong('other_entries'),
                          fileBytes: r.getObject('file_bytes').toString(),
                          hashesCompleted: 0L, errors: 0L]
            null
        }
        rows(c, 'SELECT scan_id,count(*) AS n FROM hashes WHERE scan_id IN (' + placeholders +
             ') GROUP BY scan_id', ids) { ResultSet r ->
            result[r.getLong('scan_id')]?.hashesCompleted = r.getLong('n')
            null
        }
        rows(c, 'SELECT scan_id,count(*) AS n FROM scan_errors WHERE scan_id IN (' + placeholders +
             ') GROUP BY scan_id', ids) { ResultSet r ->
            result[r.getLong('scan_id')]?.errors = r.getLong('n')
            null
        }
        result
    }

    private static Map emptySummary() {
        [files: 0L, directories: 0L, symlinks: 0L, otherEntries: 0L,
         fileBytes: '0', hashesCompleted: 0L, errors: 0L]
    }

    private static Map scanMap(ResultSet r) {
        [scanId: r.getLong('scan_id'), name: r.getString('name'), root: r.getString('root'),
         phase: r.getString('phase'), algorithm: r.getString('algorithm'),
         createdAt: r.getTimestamp('created_at').toInstant().toString(),
         updatedAt: r.getTimestamp('updated_at').toInstant().toString()]
    }

    private static Map entryMap(ResultSet r, String root) {
        String relative = r.getString('relative_path')
        String sha = r.getString('sha256')
        [entryId: r.getLong('entry_id'), parentId: r.getLong('parent_id'),
         relativePath: relative, path: StoredPath.join(root, relative),
         filename: r.getString('filename'), kind: r.getString('kind'),
         size: String.valueOf(r.getLong('size')), modifiedSec: r.getLong('modified_sec'),
         modifiedNano: r.getInt('modified_nano'), sha256: sha,
         hashState: sha ? 'HASHED' : 'UNHASHED']
    }

    private static String encodeCursor(long scanId, long parentId, Map last) {
        String json = JsonOutput.toJson([scanId: scanId, parentId: parentId,
            rank: last.kind == 'DIRECTORY' ? 0 : 1, name: last.filename, entryId: last.entryId])
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
    }

    private static String encodeOccurrenceCursor(Map binding, Map last) {
        String json = JsonOutput.toJson(binding + [scanId: String.valueOf(last.scanId),
            relativePath: last.relativePath, entryId: String.valueOf(last.entryId)])
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
    }

    private static Map decodeOccurrenceCursor(String token, Map binding) {
        if (!token) return null
        if (token.length() > 65536) throw invalid('Invalid occurrence cursor.')
        try {
            Map value = new JsonSlurper().parseText(
                new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)) as Map
            if (binding.any { key, expected -> value[key] != expected } ||
                !(value.scanId instanceof String) || !(value.entryId instanceof String) ||
                !(value.relativePath instanceof String)) throw invalid('Invalid occurrence cursor.')
            [scanId: positiveId(value.scanId as String), relativePath: value.relativePath,
                entryId: positiveId(value.entryId as String)]
        } catch (ApiFailure failure) { throw failure }
        catch (Exception ignored) { throw invalid('Invalid occurrence cursor.') }
    }

    private static Map decodeCursor(String token, long scanId, long parentId) {
        if (!token) return null
        if (token.length() > 65536) throw invalid('Invalid directory cursor.')
        try {
            Map value = new JsonSlurper().parseText(
                new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)) as Map
            if (!(value.scanId instanceof Number) || value.scanId.longValue() != scanId ||
                !(value.parentId instanceof Number) || value.parentId.longValue() != parentId ||
                !(value.rank in [0, 1]) || !(value.name instanceof String) ||
                !(value.entryId instanceof Number) || value.entryId.longValue() < 1)
                throw invalid('Invalid directory cursor.')
            [rank: value.rank.intValue(), name: value.name, entryId: value.entryId.longValue()]
        } catch (ApiFailure failure) { throw failure }
        catch (Exception ignored) { throw invalid('Invalid directory cursor.') }
    }

    private static int pageSize(String value) { boundedInt(value, 'limit', 100, 500, 1) }
    private static int boundedInt(String value, String field, int fallback, int max, int min = 0) {
        if (!value) return fallback
        try {
            int parsed = Integer.parseInt(value)
            if (parsed < min || parsed > max) throw invalid(field + ' is out of range.')
            parsed
        } catch (NumberFormatException ignored) { throw invalid('Invalid ' + field + '.') }
    }
    private static long optionalLong(String value, String field, long fallback) {
        if (!value) return fallback
        try {
            long parsed = Long.parseLong(value)
            if (parsed < 0) throw invalid('Invalid ' + field + '.')
            parsed
        } catch (NumberFormatException ignored) { throw invalid('Invalid ' + field + '.') }
    }
    static long positiveId(String value) {
        long id = optionalLong(value, 'identifier', 0L)
        if (id < 1) throw invalid('Invalid identifier.')
        id
    }
    private static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_FILTER', HttpStatus.BAD_REQUEST, message)
    }

    private static long scalar(Connection c, String sql, List values) {
        one(c, sql, values) { ResultSet r -> r.getLong(1) } as long
    }
    private static def one(Connection c, String sql, List values, Closure mapper) {
        List found = rows(c, sql, values, mapper)
        found ? found[0] : null
    }
    private static List rows(Connection c, String sql, List values, Closure mapper) {
        List output = []
        c.prepareStatement(sql).withCloseable { PreparedStatement statement ->
            values.eachWithIndex { value, index -> statement.setObject(index + 1, value) }
            statement.executeQuery().withCloseable { ResultSet result ->
                while (result.next()) output.add(mapper.call(result))
            }
        }
        output
    }
}
