package fnord.dedup.merge

import fnord.dedup.StopToken
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CancellationException

/** JDBC has one owner. The cancellation watcher only invokes Statement.cancel(). */
class MergeSql {
    final Connection connection
    final StopToken stop
    private volatile Statement active
    private volatile boolean cancellable = true

    MergeSql(Connection connection, StopToken stop = new StopToken()) {
        this.connection = connection
        this.stop = stop
    }

    static String literal(String value) { "'" + value.replace("'", "''") + "'" }
    static String identifier(String value) { '"' + value.replace('"', '""') + '"' }

    void cancelActive() {
        if (cancellable && stop.cancelled) {
            try { active?.cancel() } catch (Exception ignored) { /* The owner may have closed it. */ }
        }
    }

    def statement(String sql, List parameters, Closure action) {
        stop.check()
        connection.prepareStatement(sql).withCloseable { s ->
            active = s
            try {
                parameters.eachWithIndex { v, i -> s.setObject(i + 1, v instanceof GString ? v.toString() : v) }
                stop.check()
                action(s)
            } catch (java.sql.SQLException failure) {
                if (stop.cancelled) throw new CancellationException('Database merge cancelled before commit')
                throw failure
            } finally { active = null }
        }
    }

    void exec(String sql, List parameters = []) { statement(sql, parameters) { it.execute(); null } }

    void each(String sql, List parameters = [], Closure consumer) {
        statement(sql, parameters) { s ->
            s.executeQuery().withCloseable { rs ->
                def md = rs.metaData
                List<String> names = (1..md.columnCount).collect { md.getColumnLabel(it).toLowerCase(Locale.ROOT) }
                while (rs.next()) {
                    stop.check()
                    Map row = [:]
                    names.eachWithIndex { n, i -> row[n] = rs.getObject(i + 1) }
                    consumer(row)
                }
            }
        }
    }

    // Only use for scalar, metadata, or explicitly bounded page queries.
    List<Map> rows(String sql, List parameters = []) {
        List<Map> rows = []
        each(sql, parameters) { rows.add(it) }
        rows
    }

    Object scalar(String sql, List parameters = []) { rows(sql, parameters)[0].values().first() }

    void commit() {
        stop.check()
        // COMMIT is the linearization point. A signal arriving afterwards cannot undo it.
        cancellable = false
        connection.commit()
    }
}
