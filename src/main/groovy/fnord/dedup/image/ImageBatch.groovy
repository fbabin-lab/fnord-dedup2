package fnord.dedup.image

import fnord.dedup.store.DuckStore
import org.duckdb.DuckDBAppender

/** Bounded, schema-directed primitive appends. All integer columns are BIGINT. */
class ImageBatch {
    static final Set<String> TABLES=['image_components','image_partitions','image_filesystems','image_entries','image_errors'] as Set
    private final DuckStore store
    private final int limit
    private final Map<String,List<List>> pending=[:]
    private int count=0
    ImageBatch(DuckStore store,int limit) { this.store=store; this.limit=Math.min(limit,4096) }
    void add(String table,List values) {
        if (!TABLES.contains(table)) throw new IllegalArgumentException('Unknown image bulk table')
        pending.computeIfAbsent(table) { [] }.add(values)
        if (++count>=limit) flush()
    }
    void flush() {
        if (!count) return
        store.transaction {
            store.exec('SELECT 1') // Activate native transaction before Appender.
            pending.each { String table,List<List> rows ->
                store.connection.createAppender('main',table).withCloseable { DuckDBAppender a ->
                    rows.each { List row ->
                        a.beginRow()
                        row.each { Object v ->
                            if (v==null) a.appendNull()
                            else if (v instanceof Number) a.append(((Number)v).longValue())
                            else if (v instanceof Boolean) a.append((boolean)v)
                            else a.append(v.toString())
                        }
                        a.endRow()
                    }
                }
            }
        }
        pending.clear(); count=0
    }
}
