package fnord.dedup.web

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.http.HttpStatus

import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/** SQL-only expansion of published archive occurrences. Never reads a source path. */
final class ArchiveQueries {
    static final int MAX_DEPTH = 64
    static final String GOOD_MEMBER = "m.kind='FILE' AND m.integrity='READ_OK' AND NOT coalesce(m.encrypted,false) AND m.actual_size>=0 AND regexp_full_match(m.sha256,'[0-9a-f]{64}')"

    static Map scope(Connection c, List<Long> ids, boolean archives) {
        if (ids != null) {
            long count = (rows(c, 'SELECT count(*) AS n FROM scans WHERE scan_id IN (' + marks(ids.size()) + ')', ids)[0].n as Number).longValue()
            if (count != ids.size()) throw new ApiFailure('SCAN_NOT_FOUND', HttpStatus.NOT_FOUND, 'One or more selected scans do not exist.')
        }
        String selected = 'SELECT * FROM scans' + (ids == null ? '' : ' WHERE scan_id IN (' + marks(ids.size()) + ')')
        String locations = archives ? '''locations AS (
            SELECT j.scan_id,j.first_entry AS root_entry,j.result_id,''::VARCHAR AS chain,
                e.relative_path AS label,0 AS depth,[j.result_id] AS trail
            FROM archive_jobs j JOIN selected s USING(scan_id)
            JOIN entries e ON e.scan_id=j.scan_id AND e.entry_id=j.first_entry
            JOIN archive_results r ON r.result_id=j.result_id
            WHERE j.status IN ('COMPLETE','PARTIAL') AND r.state IN ('COMPLETE','PARTIAL')
            UNION ALL
            SELECT l.scan_id,l.root_entry,n.child_result_id,
                CASE WHEN l.chain='' THEN '' ELSE l.chain||'.' END||CAST(n.source_ordinal AS VARCHAR),
                l.label||'!/'||coalesce(m.relative_path,'[member '||CAST(n.source_ordinal AS VARCHAR)||']'),
                l.depth+1,list_append(l.trail,n.child_result_id)
            FROM locations l JOIN archive_nested n ON n.parent_result_id=l.result_id
            JOIN archive_results r ON r.result_id=n.child_result_id
            JOIN archive_members m ON m.result_id=l.result_id AND m.ordinal=n.source_ordinal
            WHERE r.state IN ('COMPLETE','PARTIAL') AND l.depth<64
                AND NOT list_contains(l.trail,n.child_result_id)
        )''' : '''locations AS (SELECT NULL::BIGINT AS scan_id,NULL::BIGINT AS root_entry,
            NULL::VARCHAR AS result_id,''::VARCHAR AS chain,''::VARCHAR AS label,
            0 AS depth,[]::VARCHAR[] AS trail WHERE false)'''
        String memberRows = archives ? '''SELECT l.scan_id,l.root_entry,l.result_id,l.chain,l.label,
                m.ordinal,m.relative_path,m.filename,m.kind,m.actual_size,m.declared_size,
                m.modified_sec,m.modified_nano,m.integrity,m.encrypted,m.diagnostic,m.recovered_sha256,
                CASE WHEN ''' + GOOD_MEMBER + ''' THEN m.sha256 ELSE NULL END AS sha256
            FROM locations l JOIN archive_members m USING(result_id)''' : '''SELECT
                NULL::BIGINT AS scan_id,NULL::BIGINT AS root_entry,NULL::VARCHAR AS result_id,
                ''::VARCHAR AS chain,''::VARCHAR AS label,NULL::BIGINT AS ordinal,
                NULL::VARCHAR AS relative_path,NULL::VARCHAR AS filename,NULL::VARCHAR AS kind,
                NULL::BIGINT AS actual_size,NULL::BIGINT AS declared_size,NULL::BIGINT AS modified_sec,
                NULL::INTEGER AS modified_nano,NULL::VARCHAR AS integrity,NULL::BOOLEAN AS encrypted,
                NULL::VARCHAR AS diagnostic,NULL::VARCHAR AS recovered_sha256,NULL::VARCHAR AS sha256 WHERE false'''
        String sql = 'WITH RECURSIVE selected AS (' + selected + '), ' + locations +
            ', member_rows AS (' + memberRows + '''), evidence AS (
                SELECT e.size,h.sha256,'FILESYSTEM' AS storage_kind,e.scan_id,e.entry_id,
                    e.parent_id,NULL::BIGINT AS root_entry,''::VARCHAR AS chain,
                    NULL::BIGINT AS ordinal,e.relative_path AS location,e.filename
                FROM entries e JOIN selected s USING(scan_id) JOIN hashes h USING(scan_id,entry_id)
                WHERE e.kind='FILE' AND s.algorithm='SHA-256' AND e.size>=0
                    AND regexp_full_match(h.sha256,'[0-9a-f]{64}')
                UNION ALL
                SELECT actual_size,sha256,'ARCHIVE_MEMBER',scan_id,NULL::BIGINT,NULL::BIGINT,
                    root_entry,chain,ordinal,label||'!/'||coalesce(relative_path,'[unavailable name]'),filename
                FROM member_rows WHERE sha256 IS NOT NULL
            ), content_groups AS (
                SELECT size,sha256,count(*) FILTER (WHERE storage_kind='FILESYSTEM') AS filesystem_count,
                    count(*) FILTER (WHERE storage_kind='ARCHIVE_MEMBER') AS archive_count,
                    count(*) AS occurrences FROM evidence GROUP BY size,sha256
            ) '''
        [sql: sql, values: ids ?: [], archives: archives]
    }

    /** Refuse cyclic/deeper graphs rather than presenting a silently incomplete total. */
    static void checkGraph(Connection c, Map query) {
        if (!query.archives) return
        List<Map> bad = rows(c, query.sql + '''SELECT 1 AS bad FROM locations l
            JOIN archive_nested n ON n.parent_result_id=l.result_id
            JOIN archive_results r ON r.result_id=n.child_result_id
            WHERE r.state IN ('COMPLETE','PARTIAL')
                AND (l.depth>=64 OR list_contains(l.trail,n.child_result_id)) LIMIT 1''', query.values)
        if (bad) throw new ApiFailure('ARCHIVE_GRAPH_LIMIT', HttpStatus.UNPROCESSABLE_ENTITY,
            'The recorded archive graph contains a cycle or exceeds 64 nested levels. No complete duplicate total can be reported.')
    }

    static Map summary(Connection c, Map query) {
        Map totals = rows(c, query.sql + '''SELECT count(*) AS groups,
            coalesce(sum(filesystem_count),0) AS filesystem_candidates,
            coalesce(sum(archive_count),0) AS archive_candidates,
            coalesce(sum(size::HUGEINT*filesystem_count),0) AS filesystem_bytes,
            coalesce(sum(size::HUGEINT*archive_count),0) AS archive_bytes,
            count(*) FILTER (WHERE archive_count>0) AS archive_groups
            FROM content_groups WHERE occurrences>1''', query.values)[0]
        Map coverage = rows(c, query.sql + '''SELECT count(*) AS members,
            count(*) FILTER (WHERE kind='FILE') AS files,
            count(*) FILTER (WHERE sha256 IS NOT NULL) AS hashed,
            count(*) FILTER (WHERE kind='FILE' AND sha256 IS NULL) AS unresolved
            FROM member_rows''', query.values)[0]
        Map jobs = query.archives ? rows(c, query.sql + '''SELECT count(*) AS roots,
            count(*) FILTER (WHERE j.status='PARTIAL') AS partial,
            count(*) FILTER (WHERE j.status='SKIPPED') AS skipped,
            count(*) FILTER (WHERE j.status NOT IN ('COMPLETE','PARTIAL','SKIPPED')) AS unfinished
            FROM archive_jobs j JOIN selected USING(scan_id)''', query.values)[0] :
            [roots: 0, partial: 0, skipped: 0, unfinished: 0]
        [available: query.archives,
         filesystem: [candidateFiles: totals.filesystem_candidates.toString(), candidateBytes: totals.filesystem_bytes.toString()],
         archive: [candidateMembers: totals.archive_candidates.toString(), candidateLogicalBytes: totals.archive_bytes.toString(),
            groups: totals.archive_groups.toString(), directCleanupBytes: '0'],
         coverage: [members: coverage.members.toString(), files: coverage.files.toString(),
            hashedMembers: coverage.hashed.toString(), unresolvedMembers: coverage.unresolved.toString(),
            rootArchives: jobs.roots.toString(), partialRoots: jobs.partial.toString(),
            skippedRoots: jobs.skipped.toString(), unfinishedRoots: jobs.unfinished.toString()],
         semantics: 'All matching observations, including keepers; archive bytes are uncompressed logical bytes, not reclaimable disk space. Archive members are excluded from ordinary cleanup scenarios.']
    }

    static void decorate(Connection c, Map query, List<Map> items) {
        List<Map> identities = items.findAll { it.kind == 'FILE' && it.sha256 && it.algorithm == 'SHA-256' }
            .unique { it.size.toString() + ':' + it.sha256 }
        if (identities.size() > 500) throw invalid('Unbounded archive evidence page.')
        Map counts = [:]
        if (identities) {
            List values = []
            identities.each { values.addAll([Long.parseLong(it.size.toString()), it.sha256]) }
            rows(c, query.sql + 'SELECT g.* FROM content_groups g JOIN (VALUES ' +
                (['(CAST(? AS BIGINT),CAST(? AS VARCHAR))'] * identities.size()).join(',') +
                ') AS wanted(size,sha256) USING(size,sha256)', query.values + values).each {
                    counts[it.size.toString() + ':' + it.sha256] = it
                }
        }
        items.each { Map item ->
            Map count = item.kind == 'FILE' && item.algorithm == 'SHA-256' ? counts[item.size.toString() + ':' + item.sha256] : null
            item.filesystemOccurrenceCount = count?.filesystem_count ?: 0L
            item.archiveOccurrenceCount = count?.archive_count ?: 0L
            item.duplicateCount = item.sha256 ? (count?.occurrences ?: 0L) : null
            item.duplicateCandidate = (item.duplicateCount ?: 0L) > 1L
            item.countScope = 'ALL_SCANS'
        }
    }

    static List<Long> scanIds(Object raw) {
        if (!(raw instanceof List) || raw.empty || raw.size() > 1000) throw invalid('Select between 1 and 1,000 scans explicitly.')
        List<Long> result = raw.collect { id(it) }
        if (result.toSet().size() != result.size()) throw invalid('Repeated scan identifiers are not allowed.')
        result.sort()
    }
    static long id(Object raw) {
        if (raw == null || !(raw.toString() ==~ /[1-9][0-9]*/)) throw invalid('A positive identifier is required.')
        try { Long.parseLong(raw.toString()) }
        catch (NumberFormatException ignored) { throw invalid('Identifier is out of range.') }
    }
    static long nonnegative(Object raw) {
        if (raw == null || !(raw.toString() ==~ /0|[1-9][0-9]*/)) throw invalid('A nonnegative byte size is required.')
        try { Long.parseLong(raw.toString()) }
        catch (NumberFormatException ignored) { throw invalid('Byte size is out of range.') }
    }
    static int limit(Object raw) {
        if (raw == null || raw == '') return 100
        long n = id(raw)
        if (n > 500) throw invalid('Page size must be between 1 and 500.')
        (int)n
    }
    static String marks(int n) { (['?'] * n).join(',') }
    static String fingerprint(Object scope) {
        HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(scope).getBytes('UTF-8')))
    }
    static String encode(Map value) { Base64.urlEncoder.withoutPadding().encodeToString(JsonOutput.toJson(value).getBytes('UTF-8')) }
    static Map cursor(String token, String binding) {
        if (!token) return null
        try {
            if (token.length() > 65536) throw invalid('Invalid archive cursor.')
            Object parsed = new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(token),'UTF-8'))
            if (!(parsed instanceof Map) || parsed.binding != binding) throw invalid('Archive cursor does not match this result and scope.')
            parsed as Map
        } catch (ApiFailure failure) { throw failure }
        catch (Exception ignored) { throw invalid('Invalid archive cursor.') }
    }
    static List<Map> rows(Connection c, String sql, List values = []) {
        List<Map> output = []
        try {
            c.prepareStatement(sql).withCloseable { PreparedStatement statement ->
                statement.queryTimeout = 30
                values.eachWithIndex { value, index -> statement.setObject(index+1, value) }
                statement.executeQuery().withCloseable { ResultSet r ->
                    int columns = r.metaData.columnCount
                    while (r.next()) {
                        Map item = [:]
                        for (int i=1;i<=columns;i++) item[r.metaData.getColumnLabel(i)] = r.getObject(i)
                        output.add(item)
                    }
                }
            }
        } catch (SQLException failure) {
            if ((failure.message ?: '').toLowerCase().contains('interrupt') ||
                (failure.message ?: '').toLowerCase().contains('out of memory'))
                throw new ApiFailure('ARCHIVE_QUERY_LIMIT', HttpStatus.UNPROCESSABLE_ENTITY,
                    'Archive analysis exceeded its query or memory limit. Select fewer scans; no complete total is available.', failure)
            throw failure
        }
        output
    }
    static Map page(List<Map> items, int limit, Closure token) {
        boolean more = items.size() > limit
        if (more) items.remove(items.size()-1)
        [items: items, page: [limit:limit, hasMore:more, nextCursor:more ? token.call(items.last()) : null]]
    }
    static ApiFailure invalid(String message) { new ApiFailure('INVALID_ARCHIVE_REQUEST', HttpStatus.BAD_REQUEST, message) }
}
