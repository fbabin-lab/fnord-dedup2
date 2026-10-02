package fnord.dedup.archive

import fnord.dedup.store.DuckStore
import org.duckdb.DuckDBAppender

/** Bounded row batches, including an explicit native transaction before any appender. */
class ArchiveBatch {
    private static final Map<String, Set<Integer>> INT_COLUMNS = [
        archive_inputs: [3,8] as Set, archive_volumes: [1,2] as Set,
        archive_members: [8,17] as Set, archive_nested: [] as Set, archive_errors: [] as Set
    ]
    private final DuckStore store
    private final int limit
    private final Map<String,List<List>> pending = [:]
    private int count = 0

    ArchiveBatch(DuckStore store, int limit) { this.store = store; this.limit = Math.min(limit, 1024) }

    void add(String table, List values) {
        if (!(table in ['archive_inputs','archive_members','archive_volumes','archive_nested','archive_errors'])) {
            throw new IllegalArgumentException('Not an archive bulk table')
        }
        pending.computeIfAbsent(table) { [] }.add(values)
        if (++count >= limit) flush()
    }

    void flush() {
        if (count == 0) return
        store.transaction {
            // DuckDB JDBC starts manual transactions lazily on a SQL statement,
            // not on createAppender/append. This is required for rollback safety.
            store.exec('SELECT 1')
            pending.each { String table, List<List> rows ->
                store.connection.createAppender('main', table).withCloseable { DuckDBAppender a ->
                    rows.each { List row ->
                        a.beginRow()
                        row.eachWithIndex { Object v, int column ->
                            if (v == null) a.appendNull()
                            else if (v instanceof Boolean) a.append((boolean) v)
                            else if (v instanceof Number && INT_COLUMNS[table].contains(column)) a.append(((Number) v).intValue())
                            else if (v instanceof Number) a.append(((Number) v).longValue())
                            else a.append(v.toString())
                        }
                        a.endRow()
                    }
                }
            }
        }
        pending.clear(); count = 0
    }
}
