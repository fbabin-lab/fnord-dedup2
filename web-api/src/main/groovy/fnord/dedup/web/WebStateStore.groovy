package fnord.dedup.web

import fnord.dedup.store.DatabaseLock
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock

/** Web-owned state. This database is always distinct from the read-only scanner database. */
@Service
class WebStateStore implements AutoCloseable {
    private static final Set<String> TABLES = ['web_schema_info', 'saved_searches'] as Set<String>
    private final String configuredPath
    private final ScannerDatabase scanner
    private final ReentrantLock access = new ReentrantLock(true)
    private Connection connection
    private DatabaseLock processLock

    WebStateStore(@Value('${dedup.web.state-database:}') String configuredPath,
                  ScannerDatabase scanner) {
        this.configuredPath = configuredPath?.trim()
        this.scanner = scanner
    }

    Map list() {
        String source = scanner.sourceKey()
        withState { Connection current ->
            List<Map> items = rows(current, '''SELECT id,name,description,request_json,created_at,updated_at
                FROM saved_searches WHERE scanner_path=?
                ORDER BY lower(name),name,id LIMIT 501''', [source]) { ResultSet result -> savedMap(result) }
            boolean truncated = items.size() > 500
            if (truncated) items.remove(items.size() - 1)
            [items: items, limit: 500, truncated: truncated]
        } as Map
    }

    Map get(String id) {
        String validId = validId(id)
        String source = scanner.sourceKey()
        withState { Connection current -> required(current, source, validId) } as Map
    }

    Map create(Map body) {
        Map value = normalizeBody(body)
        String source = scanner.sourceKey()
        withState { Connection current ->
            if (existsByName(current, source, value.name as String, null)) throw conflict()
            String id = UUID.randomUUID().toString()
            current.prepareStatement('''INSERT INTO saved_searches
                (id,scanner_path,name,description,request_json,created_at,updated_at)
                VALUES (?,?,?,?,?,current_timestamp,current_timestamp)''').withCloseable { PreparedStatement statement ->
                bind(statement, [id, source, value.name, value.description, value.requestJson])
                statement.executeUpdate()
            }
            required(current, source, id)
        } as Map
    }

    Map update(String id, Map body) {
        String validId = validId(id)
        Map value = normalizeBody(body)
        String source = scanner.sourceKey()
        withState { Connection current ->
            required(current, source, validId)
            if (existsByName(current, source, value.name as String, validId)) throw conflict()
            current.prepareStatement('''UPDATE saved_searches
                SET name=?,description=?,request_json=?,updated_at=current_timestamp
                WHERE scanner_path=? AND id=?''').withCloseable { PreparedStatement statement ->
                bind(statement, [value.name, value.description, value.requestJson, source, validId])
                statement.executeUpdate()
            }
            required(current, source, validId)
        } as Map
    }

    void delete(String id) {
        String validId = validId(id)
        String source = scanner.sourceKey()
        withState { Connection current ->
            current.prepareStatement('DELETE FROM saved_searches WHERE scanner_path=? AND id=?').withCloseable {
                PreparedStatement statement ->
                bind(statement, [source, validId])
                if (statement.executeUpdate() != 1) throw notFound()
            }
            null
        }
    }

    private def withState(Closure action) {
        access.lock()
        try {
            Connection current = openIfNeeded()
            action.call(current)
        } catch (ApiFailure failure) { throw failure }
        catch (SQLException error) {
            throw new ApiFailure('STATE_DATABASE_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                'The saved-search database request could not be completed.', error)
        } finally { access.unlock() }
    }

    private Connection openIfNeeded() {
        if (connection != null) return connection
        Path path
        DatabaseLock lock
        Connection opened
        try {
            path = resolvePath()
            rejectScannerDatabase(path)
            Files.createDirectories(path.parent)
            path = Files.exists(path) ? path.toRealPath() : path.parent.toRealPath().resolve(path.fileName)
            rejectScannerDatabase(path)
            try {
                lock = new DatabaseLock(Path.of(path.toString() + '.lock'))
            } catch (IllegalStateException inUse) {
                throw new ApiFailure('STATE_DATABASE_LOCKED', HttpStatus.LOCKED,
                    'The saved-search database is already in use by another web process.', inUse)
            }
            opened = DriverManager.getConnection('jdbc:duckdb:' + path)
            initialize(opened)
            processLock = lock
            connection = opened
            return connection
        } catch (ApiFailure failure) {
            try { opened?.close() } finally { lock?.close() }
            throw failure
        } catch (IOException | SQLException error) {
            try { opened?.close() } finally { lock?.close() }
            throw new ApiFailure('STATE_DATABASE_OPEN_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                'The saved-search database could not be opened.', error)
        } catch (RuntimeException error) {
            try { opened?.close() } finally { lock?.close() }
            throw error
        }
    }

    private Path resolvePath() {
        if (configuredPath) return Path.of(configuredPath).toAbsolutePath().normalize()
        Path.of(System.getProperty('user.home'), '.fnord-dedup2', 'web.duckdb')
            .toAbsolutePath().normalize()
    }

    private void rejectScannerDatabase(Path candidate) {
        if (!scanner.configuredPath) return
        Path scannerPath = Path.of(scanner.configuredPath).toAbsolutePath().normalize()
        boolean same = candidate.toAbsolutePath().normalize() == scannerPath
        if (!same && Files.exists(candidate) && Files.exists(scannerPath)) {
            try { same = Files.isSameFile(candidate, scannerPath) }
            catch (IOException ignored) { }
        }
        if (same) throw new ApiFailure('INVALID_STATE_DATABASE', HttpStatus.BAD_REQUEST,
            'The saved-search database must be different from the scanner database.')
    }

    private static void initialize(Connection current) {
        Set<String> tables = [] as Set<String>
        current.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema='main'")
            .withCloseable { PreparedStatement statement ->
                statement.executeQuery().withCloseable { ResultSet result ->
                    while (result.next()) tables.add(result.getString(1))
                }
            }
        if (!tables.contains('web_schema_info')) {
            if (tables) throw unsupported()
            boolean autoCommit = current.autoCommit
            current.autoCommit = false
            try {
                current.createStatement().withCloseable { statement ->
                    statement.execute('CREATE TABLE web_schema_info (version INTEGER NOT NULL)')
                    statement.execute('INSERT INTO web_schema_info VALUES (1)')
                    statement.execute('''CREATE TABLE saved_searches (
                        id VARCHAR PRIMARY KEY,
                        scanner_path VARCHAR NOT NULL,
                        name VARCHAR NOT NULL,
                        description VARCHAR NOT NULL,
                        request_json VARCHAR NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL,
                        UNIQUE(scanner_path,name))''')
                }
                current.commit()
            } catch (Throwable error) {
                try { current.rollback() } catch (Throwable rollback) { error.addSuppressed(rollback) }
                throw error
            } finally { current.autoCommit = autoCommit }
            tables = TABLES
        }
        if (tables != TABLES) throw unsupported()
        List<Integer> versions = rows(current, 'SELECT version FROM web_schema_info', []) {
            ResultSet result -> result.getInt(1)
        } as List<Integer>
        if (versions != [1]) throw unsupported()
    }

    private static Map normalizeBody(Map body) {
        if (body == null) throw invalid('Saved search body is required.')
        Set<String> allowed = ['name', 'description', 'request'] as Set<String>
        if (!allowed.containsAll(body.keySet())) throw invalid('Unknown saved-search field.')
        if (!(body.name instanceof String)) throw invalid('Saved search name is required.')
        String name = (body.name as String).trim()
        if (!name || name.length() > 120 || name.indexOf(0) >= 0)
            throw invalid('Saved search name must contain 1..120 characters.')
        String description = body.description == null ? '' : body.description as String
        if (!(body.description == null || body.description instanceof String) ||
            description.length() > 2000 || description.indexOf(0) >= 0)
            throw invalid('Saved search description must be at most 2000 characters.')
        Map request = FileSearchService.normalizedSavedRequest(body.request)
        String requestJson = JsonOutput.toJson(request)
        if (requestJson.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw invalid('Saved search request is too large.')
        [name: name, description: description, requestJson: requestJson]
    }

    private static Map required(Connection current, String source, String id) {
        List<Map> found = rows(current, '''SELECT id,name,description,request_json,created_at,updated_at
            FROM saved_searches WHERE scanner_path=? AND id=? LIMIT 1''', [source, id]) {
            ResultSet result -> savedMap(result)
        }
        if (!found) throw notFound()
        found[0]
    }

    private static boolean existsByName(Connection current, String source, String name, String exceptId) {
        String sql = 'SELECT 1 FROM saved_searches WHERE scanner_path=? AND name=?'
        List values = [source, name]
        if (exceptId) { sql += ' AND id<>?'; values.add(exceptId) }
        sql += ' LIMIT 1'
        !rows(current, sql, values) { ResultSet ignored -> true }.empty
    }

    private static Map savedMap(ResultSet result) {
        [id: result.getString('id'), name: result.getString('name'),
         description: result.getString('description'),
         request: new JsonSlurper().parseText(result.getString('request_json')),
         createdAt: result.getTimestamp('created_at').toInstant().toString(),
         updatedAt: result.getTimestamp('updated_at').toInstant().toString()]
    }

    private static String validId(String id) {
        try { UUID.fromString(id).toString() }
        catch (Exception ignored) { throw invalid('Invalid saved-search identifier.') }
    }

    private static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_SAVED_SEARCH', HttpStatus.BAD_REQUEST, message)
    }
    private static ApiFailure notFound() {
        new ApiFailure('SAVED_SEARCH_NOT_FOUND', HttpStatus.NOT_FOUND, 'Saved search not found.')
    }
    private static ApiFailure conflict() {
        new ApiFailure('SAVED_SEARCH_EXISTS', HttpStatus.CONFLICT,
            'A saved search with this name already exists for this scanner database.')
    }
    private static ApiFailure unsupported() {
        new ApiFailure('UNSUPPORTED_STATE_SCHEMA', HttpStatus.UNPROCESSABLE_ENTITY,
            'The saved-search database schema is not supported.')
    }

    private static List rows(Connection current, String sql, List values, Closure mapper) {
        List output = []
        current.prepareStatement(sql).withCloseable { PreparedStatement statement ->
            bind(statement, values)
            statement.executeQuery().withCloseable { ResultSet result ->
                while (result.next()) output.add(mapper.call(result))
            }
        }
        output
    }

    private static void bind(PreparedStatement statement, List values) {
        values.eachWithIndex { value, index -> statement.setObject(index + 1, value) }
    }

    @PreDestroy
    @Override void close() {
        access.lock()
        try {
            try { connection?.close() } finally {
                connection = null
                processLock?.close()
                processLock = null
            }
        } finally { access.unlock() }
    }
}
