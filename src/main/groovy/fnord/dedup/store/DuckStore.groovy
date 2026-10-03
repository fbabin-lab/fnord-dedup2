package fnord.dedup.store

import fnord.dedup.ScanOptions
import fnord.dedup.path.StoredPath
import fnord.dedup.path.NativeFiles
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import java.sql.ResultSet

/** Single-owner JDBC repository. Hash workers never receive this object. */
class DuckStore implements AutoCloseable {
    final Path database
    final Path tempDirectory
    final DuckDBConnection connection
    private final DatabaseLock lock

    DuckStore(Path requested, ScanOptions options) {
        options.validate()
        Path absolute = requested.toAbsolutePath().normalize()
        Files.createDirectories(absolute.parent)
        database = Files.exists(absolute) ? absolute.toRealPath() : absolute.parent.toRealPath().resolve(absolute.fileName)
        tempDirectory = Path.of(database.toString() + '.tmp')
        lock = new DatabaseLock(Path.of(database.toString() + '.lock'))
        DuckDBConnection opened = null
        try {
            opened = (DuckDBConnection) DriverManager.getConnection('jdbc:duckdb:' + database)
            connection = opened
            exec("SET threads = ${options.databaseThreads}")
            exec("SET memory_limit = '${options.memoryLimit.toUpperCase(Locale.ROOT)}'")
            exec("SET temp_directory = '${tempDirectory.toString().replace("'", "''")}'")
            initialize()
        } catch (Throwable e) {
            try { opened?.close() } finally { lock.close() }
            throw e
        }
    }

    private void initialize() {
        def tables = rows("SELECT table_name FROM information_schema.tables WHERE table_schema='main'")*.table_name
        if (!tables.contains('schema_info')) {
            if (!tables.empty) throw new IllegalArgumentException('Refusing to initialize an unrelated, nonempty database')
            String schema = getClass().getResourceAsStream('/schema.sql').withCloseable { it.getText('UTF-8') }
            transaction {
                schema.split(';').findAll { it.trim() }.each { exec(it) }
            }
        }
        def versions = rows('SELECT version FROM schema_info')
        if (versions.size() != 1 || versions[0].version != 1) {
            throw new IllegalArgumentException('Unsupported database schema; use a database created by this application version')
        }
    }

    void exec(String sql, Object... args) {
        connection.prepareStatement(sql).withCloseable { statement ->
            bind(statement, args)
            statement.execute()
        }
    }

    List<Map> rows(String sql, Object... args) {
        List<Map> result = []
        eachRow(sql, args) { Map row -> result.add(row) }
        result
    }

    void eachRow(String sql, Object[] args, Closure consumer) {
        connection.prepareStatement(sql).withCloseable { statement ->
            bind(statement, args)
            statement.executeQuery().withCloseable { ResultSet rs ->
                def metadata = rs.metaData
                int count = metadata.columnCount
                List<String> names = (1..count).collect { metadata.getColumnLabel(it).toLowerCase(Locale.ROOT) }
                while (rs.next()) {
                    Map row = new LinkedHashMap()
                    for (int i = 1; i <= count; i++) row[names[i - 1]] = rs.getObject(i)
                    consumer.call(row)
                }
            }
        }
    }

    private static void bind(def statement, Object[] args) {
        args.eachWithIndex { Object value, int index ->
            statement.setObject(index + 1, value instanceof GString ? value.toString() : value)
        }
    }

    def transaction(Closure action) {
        if (!connection.autoCommit) throw new IllegalStateException('Nested transactions are not supported')
        connection.autoCommit = false
        try {
            def result = action.call()
            connection.commit()
            return result
        } catch (Throwable e) {
            try { connection.rollback() } catch (Throwable rollback) { e.addSuppressed(rollback) }
            throw e
        } finally { connection.autoCommit = true }
    }

    boolean excluded(Path path) {
        Path normalized = path.toAbsolutePath().normalize()
        if (normalized == database || normalized == Path.of(database.toString() + '.wal') ||
            normalized == Path.of(database.toString() + '.lock') || normalized.startsWith(tempDirectory)) return true
        if (StoredPath.windowsHost()) {
            try {
                Path canonical = NativeFiles.canonicalControlPath(normalized)
                if (canonical == database || canonical == Path.of(database.toString()+'.wal') ||
                    canonical == Path.of(database.toString()+'.lock') || canonical.startsWith(tempDirectory)) return true
            } catch (IOException | SecurityException ignored) { /* Traversal reports inaccessible paths. */ }
            // Existing aliases/hardlinks of active DB/control files must not enter the inventory.
            for (Path control : [database, Path.of(database.toString()+'.wal'), Path.of(database.toString()+'.lock')]) {
                try { if (Files.isSameFile(normalized, control)) return true }
                catch (IOException | SecurityException ignored) { /* Missing/inaccessible candidates are handled by traversal. */ }
            }
        }
        false
    }

    Map createScan(String name, Path requestedRoot) {
        StoredPath.validateRequestedRoot(requestedRoot)
        if (!name || !name.trim() || name.length() > 200) throw new IllegalArgumentException('Scan name must contain 1..200 characters')
        Path root = StoredPath.canonicalRoot(requestedRoot)
        if (NativeFiles.kind(Files.readAttributes(root, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)) != 'DIRECTORY') throw new IllegalArgumentException('Scan root must be a directory')
        if (excluded(root)) throw new IllegalArgumentException('Cannot scan the database temporary directory')
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
        transaction {
            if (!rows('SELECT scan_id FROM scans WHERE name=?', name).empty) {
                throw new IllegalArgumentException("Scan '${name}' already exists; use resume or a new name")
            }
            long id = ((Number) rows('SELECT coalesce(max(scan_id),0)+1 AS id FROM scans')[0].id).longValue()
            exec("INSERT INTO scans(scan_id,name,root,phase,next_entry_id) VALUES (?,?,?,'DISCOVERING',2)", id, name, StoredPath.storeAbsolute(root))
            def modified = attributes.lastModifiedTime().toInstant()
            exec('INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?)', id, 1L, 0L, '', root.fileName?.toString() ?: '/', 'DIRECTORY', attributes.size(), modified.epochSecond, modified.nano)
            exec('INSERT INTO directories VALUES (?,?,?,?,false)', id, 1L, 0L, '')
        }
        scan(name)
    }

    Map scan(String name) {
        def matches = rows('SELECT * FROM scans WHERE name=?', name)
        if (matches.empty) throw new IllegalArgumentException("Unknown scan: ${name}")
        matches[0]
    }

    void phase(long id, String phase) {
        exec('UPDATE scans SET phase=?,updated_at=current_timestamp WHERE scan_id=?', phase, id)
    }

    void recoverDiscovery(long id) {
        Map record = rows('SELECT active_dir FROM scans WHERE scan_id=?', id)[0]
        if (record.active_dir == null) return
        // An unfinished parent's children cannot run: pendingDirectories gates them.
        // Delete only its immediate children, then enumerate that directory again.
        transaction {
            exec('DELETE FROM directories WHERE scan_id=? AND parent_id=?', id, record.active_dir)
            exec('DELETE FROM entries WHERE scan_id=? AND parent_id=?', id, record.active_dir)
            exec('UPDATE scans SET active_dir=NULL WHERE scan_id=?', id)
        }
    }

    List<Map> pendingDirectories(long id, int limit) {
        rows('''SELECT d.entry_id,d.relative_path FROM directories d
            LEFT JOIN directories p ON p.scan_id=d.scan_id AND p.entry_id=d.parent_id
            WHERE d.scan_id=? AND NOT d.completed AND (d.parent_id=0 OR p.completed)
            ORDER BY d.entry_id LIMIT ?''', id, limit)
    }

    Map status(String name) {
        Map result = new LinkedHashMap(scan(name))
        long id = result.scan_id as long
        result.putAll(rows('''SELECT
            count(*) FILTER (WHERE kind='FILE') AS files,
            count(*) FILTER (WHERE kind='DIRECTORY') AS directories,
            count(*) FILTER (WHERE kind='SYMLINK') AS symlinks,
            count(*) FILTER (WHERE kind='OTHER') AS other_entries,
            coalesce(sum(size::HUGEINT) FILTER (WHERE kind='FILE'),0) AS total_file_bytes
            FROM entries WHERE scan_id=?''', id)[0])
        result.hashes_completed = rows('SELECT count(*) AS n FROM hashes WHERE scan_id=?', id)[0].n
        result.pending_directories = rows('SELECT count(*) AS n FROM directories WHERE scan_id=? AND NOT completed', id)[0].n
        result.errors = rows('SELECT count(*) AS n FROM scan_errors WHERE scan_id=?', id)[0].n
        result.candidate_files = rows('''SELECT coalesce(sum(n),0) AS n FROM
            (SELECT count(*) AS n FROM entries WHERE scan_id=? AND kind='FILE'
             GROUP BY size HAVING count(*)>1)''', id)[0].n
        result.remove('active_dir')
        result.remove('next_entry_id')
        result
    }

    void prepareCandidates(long id, boolean rehash) {
        transaction {
            if (rehash) exec('DELETE FROM hashes WHERE scan_id=?', id)
            exec("DELETE FROM scan_errors WHERE scan_id=? AND phase='HASHING'", id)
            phase(id, 'HASHING')
        }
        exec('''CREATE OR REPLACE TEMP TABLE hash_candidates AS
            SELECT row_number() OVER (ORDER BY e.entry_id) AS sequence,
                   e.entry_id,e.relative_path,e.size,e.modified_sec,e.modified_nano
            FROM entries e JOIN
                (SELECT size FROM entries WHERE scan_id=? AND kind='FILE'
                 GROUP BY size HAVING count(*)>1) sizes ON sizes.size=e.size
            WHERE e.scan_id=? AND e.kind='FILE' AND NOT EXISTS
                (SELECT 1 FROM hashes h WHERE h.scan_id=e.scan_id AND h.entry_id=e.entry_id)
            ORDER BY e.entry_id''', id, id)
    }

    List<Map> candidates(long after, int limit) {
        rows('SELECT * FROM hash_candidates WHERE sequence>? AND sequence<=? ORDER BY sequence', after, Math.addExact(after, (long) limit))
    }

    boolean hasErrors(long id) {
        !rows('SELECT 1 FROM scan_errors WHERE scan_id=? LIMIT 1', id).empty
    }

    void duplicateRows(String name, boolean allowPartial, Closure consumer) {
        Map scan = scan(name)
        boolean partial = !(scan.phase in ['COMPLETE', 'COMPLETE_WITH_ERRORS'])
        if (partial && !allowPartial) throw new IllegalStateException('Scan is not complete; resume it or use --allow-partial')
        long id = scan.scan_id as long
        eachRow('''WITH matched AS (
              SELECT e.*,h.sha256 FROM entries e JOIN hashes h USING (scan_id,entry_id)
              WHERE e.scan_id=? AND e.kind='FILE'
            ), grouped AS (
              SELECT size,sha256,count(*) AS copies FROM matched
              GROUP BY size,sha256 HAVING count(*)>1
            ) SELECT m.relative_path,m.filename,m.size,m.modified_sec,m.modified_nano,m.sha256,g.copies
              FROM matched m JOIN grouped g USING (size,sha256)
              ORDER BY m.size DESC,m.sha256,m.relative_path''', [id] as Object[]) { Map row ->
            row.path = StoredPath.join(scan.root as String, row.relative_path as String)
            row.partial = partial
            consumer.call(row)
        }
    }

    @Override void close() {
        try { connection.close() } finally { lock.close() }
    }
}
