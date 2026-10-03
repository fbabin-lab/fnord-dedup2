package fnord.dedup.web

import fnord.dedup.store.DatabaseLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock

/** One bounded request owns the scanner lock and a read-only connection, then closes both. */
@Service
class ScannerDatabase {
    final String configuredPath
    private final ReentrantLock localReadLock = new ReentrantLock(true)

    ScannerDatabase(@Value('${dedup.web.database:}') String configuredPath) {
        this.configuredPath = configuredPath?.trim()
    }

    Map<String, Object> registration() {
        [path: configuredPath, configured: !!configuredPath, readOnly: true]
    }

    Map<String, Object> status() {
        withConnection { Connection connection, Path path ->
            Map<String, Object> schema = SchemaInspector.inspect(connection)
            long scanCount = 0L
            connection.prepareStatement('SELECT count(*) FROM scans').withCloseable { statement ->
                statement.executeQuery().withCloseable { ResultSet result ->
                    result.next()
                    scanCount = result.getLong(1)
                }
            }
            schema + [path: path.toString(), scanCount: scanCount,
                      lastModified: Files.getLastModifiedTime(path).toInstant().toString(), readOnly: true]
        }
    }

    def withConnection(Closure action) {
        Path path = scannerPath()
        // One web process may receive simultaneous requests. Serialize its own reads
        // before taking the same exclusive sidecar lock used by CLI writers.
        localReadLock.lock()
        DatabaseLock lock
        try {
            try {
                lock = new DatabaseLock(Path.of(path.toString() + '.lock'))
            } catch (IllegalStateException inUse) {
                throw new ApiFailure('DATABASE_LOCKED', HttpStatus.LOCKED,
                    'The scan database is currently in use by fnord-dedup2.', inUse)
            } catch (IOException error) {
                throw new ApiFailure('DATABASE_OPEN_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                    'The database lock could not be opened.', error)
            }
            Properties properties = new Properties()
            properties.setProperty('duckdb.read_only', 'true')
            Connection connection
            try {
                connection = DriverManager.getConnection('jdbc:duckdb:' + path, properties)
            } catch (SQLException error) {
                throw new ApiFailure('DATABASE_OPEN_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                    'The scan database could not be opened read-only.', error)
            }
            try {
                connection.withCloseable { action.call(connection, path) }
            } catch (SQLException error) {
                throw new ApiFailure('DATABASE_QUERY_FAILED', HttpStatus.SERVICE_UNAVAILABLE,
                    'The scan database query could not be completed.', error)
            }
        } finally {
            try { lock?.close() } finally { localReadLock.unlock() }
        }
    }

    private Path scannerPath() {
        if (!configuredPath) throw new ApiFailure('DATABASE_NOT_CONFIGURED', HttpStatus.BAD_REQUEST,
            'Start the web application with --db /path/to/scans.duckdb.')
        Path requested = Path.of(configuredPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(requested)) throw new ApiFailure('DATABASE_NOT_FOUND', HttpStatus.NOT_FOUND,
            'The configured scan database does not exist or is not a regular file.')
        try {
            return requested.toRealPath()
        } catch (IOException error) {
            throw new ApiFailure('DATABASE_NOT_FOUND', HttpStatus.NOT_FOUND,
                'The configured scan database is not accessible.', error)
        }
    }
}
