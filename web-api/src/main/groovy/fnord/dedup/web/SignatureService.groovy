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

/** Content-based removal signals, owned entirely by the web-state database. */
@Service
class SignatureService {
    private final WebStateStore state
    private final ScannerDatabase scanner

    SignatureService(WebStateStore state, ScannerDatabase scanner) {
        this.state = state
        this.scanner = scanner
    }

    Map list(String limitText, String cursorText) {
        int limit = pageSize(limitText)
        String cursor = cursorText ? validId(cursorText) : null
        state.withState { Connection c ->
            List<Map> items = rows(c, 'SELECT * FROM signatures' + (cursor ? ' WHERE id>?' : '') +
                ' ORDER BY id LIMIT ?', cursor ? [cursor, limit + 1] : [limit + 1]) { signatureMap(it) }
            page(items, limit) { it.id }
        } as Map
    }

    Map get(String id) {
        String key = validId(id)
        state.withState { Connection c -> required(c, key) } as Map
    }

    Map create(Map body) {
        if (body == null || !(['scanId', 'entryId', 'tag', 'memo'] as Set).containsAll(body.keySet()))
            throw invalid('A signature requires a recorded file and optional tag and memo.')
        long scanId = positiveId(body.scanId)
        long entryId = positiveId(body.entryId)
        Map notes = normalizeNotes(body)
        // Scanner -> state is also the scenario generator's lock order.
        scanner.withConnection { Connection c, ignored ->
            SchemaInspector.inspect(c)
            List<Map> found = rows(c, '''SELECT e.kind,e.size,h.sha256,s.algorithm
                FROM entries e JOIN scans s USING (scan_id) LEFT JOIN hashes h USING (scan_id,entry_id)
                WHERE e.scan_id=? AND e.entry_id=? LIMIT 1''', [scanId, entryId]) {
                [kind: it.getString('kind'), size: it.getLong('size'),
                 sha256: it.getString('sha256'), algorithm: it.getString('algorithm')]
            }
            if (!found) throw new ApiFailure('ENTRY_NOT_FOUND', HttpStatus.NOT_FOUND, 'Recorded file not found.')
            Map file = found[0]
            if (file.kind != 'FILE') throw invalid('Only regular files can be added to the signature store.')
            if (file.algorithm != 'SHA-256') throw new ApiFailure('UNSUPPORTED_ALGORITHM',
                HttpStatus.UNPROCESSABLE_ENTITY, 'Signatures require SHA-256 observations.')
            if (!file.sha256) throw new ApiFailure('HASH_UNAVAILABLE', HttpStatus.UNPROCESSABLE_ENTITY,
                'This file has no saved hash. Run the CLI hash command with --hash-complete, then refresh.')
            if (file.size < 0 || !(file.sha256 ==~ /[0-9a-f]{64}/))
                throw new ApiFailure('INVALID_INVENTORY', HttpStatus.UNPROCESSABLE_ENTITY,
                    'The recorded size or SHA-256 is invalid.')
            state.transaction { Connection current ->
                if (rows(current, 'SELECT id FROM signatures WHERE size=? AND sha256=?',
                    [file.size, file.sha256]) { it.getString(1) })
                    throw new ApiFailure('SIGNATURE_EXISTS', HttpStatus.CONFLICT,
                        'This content already has a signature. Refresh and edit its tag or memo.')
                String id = UUID.randomUUID().toString()
                execute(current, '''INSERT INTO signatures
                    VALUES (?,'SHA-256',?,?,?,?,current_timestamp,current_timestamp)''',
                    [id, file.size, file.sha256, notes.tag, notes.memo])
                required(current, id)
            }
        } as Map
    }

    Map update(String id, Map body) {
        String key = validId(id)
        if (body == null || !(['tag', 'memo'] as Set).containsAll(body.keySet()))
            throw invalid('Only the tag and memo can be edited.')
        Map notes = normalizeNotes(body)
        state.transaction { Connection c ->
            required(c, key)
            execute(c, 'UPDATE signatures SET tag=?,memo=?,updated_at=current_timestamp WHERE id=?',
                [notes.tag, notes.memo, key])
            required(c, key)
        } as Map
    }

    void delete(String id) {
        String key = validId(id)
        state.transaction { Connection c ->
            required(c, key)
            execute(c, 'DELETE FROM signatures WHERE id=?', [key])
            null
        }
    }

    Map entrySignals(Map entry) { decorate([entry]); entry }

    Map pageSignals(Map result, boolean groups = false) {
        decorate(result.items as List<Map>, groups)
        if (result.group) decorate([result.group as Map], true)
        result
    }

    /** One bounded lookup per page; never load the signature store into application memory. */
    private void decorate(List<Map> items, boolean groups = false) {
        List<Map> evidence = items.findAll { (groups || it.kind == 'FILE') && it.sha256 &&
            (!it.algorithm || it.algorithm == 'SHA-256') }
            .unique { it.size.toString() + ':' + it.sha256 }
        Map<String, Map> matches = [:]
        if (evidence) {
            if (evidence.size() > 500) throw new IllegalArgumentException('Unbounded signature page')
            List values = []
            evidence.each { values.addAll([Long.parseLong(it.size.toString()), it.sha256]) }
            state.withState { Connection c ->
                rows(c, '''SELECT s.* FROM signatures s JOIN (VALUES ''' +
                    (['(CAST(? AS BIGINT),CAST(? AS VARCHAR))'] * evidence.size()).join(',') +
                    ''') AS evidence(size,sha256) ON s.size=evidence.size AND s.sha256=evidence.sha256
                    WHERE s.algorithm='SHA-256' ''', values) { ResultSet r ->
                    Map signature = signatureMap(r)
                    matches[signature.size + ':' + signature.sha256] = signature
                }
            }
        }
        items.each { Map item ->
            Map signature = (groups || item.kind == 'FILE') && (!item.algorithm || item.algorithm == 'SHA-256') ?
                matches[item.size.toString() + ':' + item.sha256] : null
            item.signature = signature
            item.signatureMatch = signature != null
            item.duplicateCandidate = (groups || item.kind == 'FILE') &&
                (!item.algorithm || item.algorithm == 'SHA-256') &&
                (((groups ? item.occurrences : item.duplicateCount) ?: 0) > 1)
            item.removalCandidate = signature != null
        }
    }

    /** Include singletons and every matching copy across the configured scanner database. */
    Map matches(String id, String limitText, String cursorText) {
        Map signature = get(id)
        int limit = pageSize(limitText)
        Map cursor = decodeCursor(cursorText, signature.id as String)
        scanner.withConnection { Connection c, ignored ->
            SchemaInspector.inspect(c)
            String sql = '''SELECT e.*,s.name AS scan_name,s.root AS scan_root FROM entries e
                JOIN scans s USING (scan_id) JOIN hashes h USING (scan_id,entry_id)
                WHERE e.kind='FILE' AND s.algorithm='SHA-256' AND e.size=? AND h.sha256=?'''
            List values = [Long.parseLong(signature.size as String), signature.sha256]
            if (cursor) {
                sql += ' AND (e.scan_id>? OR (e.scan_id=? AND e.entry_id>?))'
                values.addAll([cursor.scanId, cursor.scanId, cursor.entryId])
            }
            sql += ' ORDER BY e.scan_id,e.entry_id LIMIT ?'
            values.add(limit + 1)
            List<Map> items = rows(c, sql, values) { ResultSet r ->
                [scanId: r.getLong('scan_id'), scanName: r.getString('scan_name'),
                 entryId: r.getLong('entry_id'), parentId: r.getLong('parent_id'),
                 relativePath: r.getString('relative_path'), filename: r.getString('filename'),
                 path: StoredPath.join(r.getString('scan_root'), r.getString('relative_path')),
                 kind: 'FILE', size: signature.size, sha256: signature.sha256, hashState: 'HASHED',
                 modifiedSec: r.getLong('modified_sec'), modifiedNano: r.getInt('modified_nano'),
                 signature: signature, signatureMatch: true, removalCandidate: true]
            }
            Map result = page(items, limit) { Map last -> encodeCursor(signature.id as String, last) }
            long count = rows(c, '''SELECT count(*) FROM entries e JOIN scans s USING (scan_id)
                JOIN hashes h USING (scan_id,entry_id) WHERE e.kind='FILE' AND s.algorithm='SHA-256'
                AND e.size=? AND h.sha256=?''', [Long.parseLong(signature.size as String), signature.sha256]) {
                it.getLong(1)
            }[0] as long
            items.each { it.duplicateCount = count; it.duplicateCandidate = count > 1 }
            result + [signature: signature, total: count, persistedHashesOnly: true]
        } as Map
    }

    private static Map normalizeNotes(Map body) {
        [tag: textField(body.tag, 'Tag', 120).trim(), memo: textField(body.memo, 'Memo', 4000)]
    }

    private static String textField(Object value, String field, int max) {
        if (value == null) return ''
        if (!(value instanceof String) || value.length() > max || value.indexOf(0) >= 0)
            throw invalid(field + ' must be text with at most ' + max + ' characters.')
        value as String
    }

    private static Map required(Connection c, String id) {
        List<Map> found = rows(c, 'SELECT * FROM signatures WHERE id=?', [id]) { signatureMap(it) }
        if (!found) throw new ApiFailure('SIGNATURE_NOT_FOUND', HttpStatus.NOT_FOUND, 'Signature not found.')
        found[0]
    }

    private static Map signatureMap(ResultSet r) {
        [id: r.getString('id'), algorithm: r.getString('algorithm'), size: r.getString('size'),
         sha256: r.getString('sha256'), tag: r.getString('tag'), memo: r.getString('memo'),
         createdAt: r.getTimestamp('created_at').toInstant().toString(),
         updatedAt: r.getTimestamp('updated_at').toInstant().toString()]
    }

    private static Map page(List<Map> items, int limit, Closure cursor) {
        boolean more = items.size() > limit
        if (more) items.remove(items.size() - 1)
        [items: items, page: [limit: limit, hasMore: more,
            nextCursor: more ? cursor.call(items.last()) : null]]
    }

    private static String encodeCursor(String id, Map last) {
        Base64.urlEncoder.withoutPadding().encodeToString(JsonOutput.toJson(
            [id: id, scanId: last.scanId, entryId: last.entryId]).getBytes(StandardCharsets.UTF_8))
    }

    private static Map decodeCursor(String token, String id) {
        if (!token) return null
        if (token.length() > 4096) throw invalid('Invalid signature match cursor.')
        try {
            Map cursor = new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(token),
                StandardCharsets.UTF_8)) as Map
            if (cursor.id != id || (cursor.keySet() as Set) != (['id', 'scanId', 'entryId'] as Set))
                throw invalid('Invalid signature match cursor.')
            [scanId: positiveId(cursor.scanId), entryId: positiveId(cursor.entryId)]
        } catch (Exception ignored) { throw invalid('Invalid signature match cursor.') }
    }

    private static long positiveId(Object value) {
        if (!(value instanceof Number) || !(value.toString() ==~ /[1-9][0-9]*/))
            throw invalid('A positive numeric scan and entry identifier is required.')
        try { Long.parseLong(value.toString()) }
        catch (NumberFormatException ignored) { throw invalid('Identifier is out of range.') }
    }

    private static int pageSize(String value) {
        if (!value) return 100
        try {
            int number = Integer.parseInt(value)
            if (number < 1 || number > 500) throw invalid('Page size must be between 1 and 500.')
            number
        } catch (NumberFormatException ignored) { throw invalid('Invalid page size.') }
    }

    private static String validId(String id) {
        try {
            String canonical = UUID.fromString(id).toString()
            if (!canonical.equalsIgnoreCase(id)) throw invalid('Invalid signature identifier.')
            canonical
        } catch (Exception ignored) { throw invalid('Invalid signature identifier.') }
    }

    private static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_SIGNATURE', HttpStatus.BAD_REQUEST, message)
    }

    private static List rows(Connection c, String sql, List values, Closure mapper) {
        List result = []
        c.prepareStatement(sql).withCloseable { PreparedStatement statement ->
            values.eachWithIndex { value, index -> statement.setObject(index + 1, value) }
            statement.executeQuery().withCloseable { ResultSet r ->
                while (r.next()) result.add(mapper.call(r))
            }
        }
        result
    }

    private static void execute(Connection c, String sql, List values) {
        c.prepareStatement(sql).withCloseable { PreparedStatement statement ->
            values.eachWithIndex { value, index -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
