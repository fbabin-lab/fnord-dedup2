package fnord.dedup.web

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import org.springframework.http.HttpStatus

/** Callers supply fixed SQL/validated enums and finite pages or aggregates. */
final class ScenarioSql {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>()
    static def withBudget(long seconds, Closure action) {
        Long previous = DEADLINE.get()
        long proposed = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        DEADLINE.set(previous == null ? proposed : Math.min(previous, proposed))
        try { def value = action.call(); checkBudget(); value }
        catch (ApiFailure | SQLException error) { checkBudget(); throw error }
        finally { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous) }
    }
    static int remainingSeconds() {
        Long end = DEADLINE.get()
        if (end == null) return 0
        checkBudget()
        Math.max(1L, (end - System.nanoTime() + 999999999L).intdiv(1000000000L)) as int
    }
    static void checkBudget() {
        Long end = DEADLINE.get()
        if (end != null && System.nanoTime() >= end) throw new ApiFailure('SCENARIO_TIMEOUT', HttpStatus.CONFLICT,
            'The scenario exceeded its time limit. Narrow the scope; no partial snapshot was published.')
    }
    static List rows(Connection c, String sql, List values, Closure mapper) {
        List found = []
        checkBudget()
        try {
            c.prepareStatement(sql).withCloseable { PreparedStatement s ->
                s.setQueryTimeout(remainingSeconds()); bind(s, values)
                s.executeQuery().withCloseable { ResultSet r -> while (r.next()) { checkBudget(); found.add(mapper.call(r)) } }
            }
            checkBudget()
        } catch (SQLException error) { checkBudget(); throw error }
        found
    }
    static int exec(Connection c, String sql, List values = []) {
        checkBudget()
        try {
            int count = c.prepareStatement(sql).withCloseable { PreparedStatement s ->
                s.setQueryTimeout(remainingSeconds()); bind(s, values); s.executeUpdate()
            }
            checkBudget(); count
        } catch (SQLException error) { checkBudget(); throw error }
    }
    static void timeout(PreparedStatement s) {
        checkBudget()
        s.setQueryTimeout(remainingSeconds())
    }
    static void bind(PreparedStatement s, List values) {
        values.eachWithIndex { Object value, int i -> s.setObject(i + 1, value) }
    }
    static String id(String value) {
        try { UUID.fromString(value).toString() }
        catch (Exception ignored) { throw ScenarioConfig.invalid('Invalid scenario identifier.') }
    }
    static String contentId(String value) {
        if (!value || !(value ==~ /(?:0|[1-9][0-9]{0,18}):[0-9a-f]{64}/))
            throw ScenarioConfig.invalid('Invalid content group identifier.')
        ScenarioConfig.integer(value.substring(0, value.indexOf(':')), 'group size', 0, Long.MAX_VALUE)
        value
    }
    static String encode(Map binding, Map position) {
        Base64.urlEncoder.withoutPadding().encodeToString(JsonOutput.toJson(binding + position).getBytes(StandardCharsets.UTF_8))
    }
    static Map decode(String cursor, Map binding) {
        if (!cursor) return null
        try {
            if (cursor.length() > 65536) throw ScenarioConfig.invalid('Invalid scenario cursor.')
            Object value = new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(cursor), StandardCharsets.UTF_8))
            if (!(value instanceof Map) || binding.any { k, v -> value[k] != v })
                throw ScenarioConfig.invalid('Invalid or stale scenario cursor.')
            value as Map
        } catch (ApiFailure error) { throw error }
        catch (Exception ignored) { throw ScenarioConfig.invalid('Invalid scenario cursor.') }
    }
    static Map page(List items, int limit, Closure next) {
        boolean more = items.size() > limit
        if (more) items.remove(items.size() - 1)
        [limit: limit, hasMore: more, nextCursor: more ? next.call(items.last()) : null]
    }
}
