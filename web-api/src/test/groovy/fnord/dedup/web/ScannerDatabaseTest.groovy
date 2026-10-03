package fnord.dedup.web

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.store.DatabaseLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertThrows

class ScannerDatabaseTest {
    @TempDir Path work

    @Test void opensScannerDatabaseReadOnlyAndReportsOptionalSchemas() {
        Path input = Files.createDirectory(work.resolve('input'))
        Files.writeString(input.resolve('one'), 'same')
        Files.writeString(input.resolve('two'), 'same')
        Path database = work.resolve('scans.duckdb')
        Dedup.open(database, new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable {
            it.scan('one scan', input)
        }
        ScannerDatabase reader = new ScannerDatabase(database.toString())
        Map status = reader.status()
        assert status.scanCount == 1L
        assert status.baseSchema == 1
        assert status.archiveSchema == null
        assert status.imageSchema == null
        assert status.readOnly
        assert status.path == database.toRealPath().toString()
        assertThrows(Exception) {
            reader.withConnection { connection, ignored ->
                connection.createStatement().withCloseable { it.execute('CREATE TABLE web_should_never_write (x INT)') }
            }
        }
        assert reader.status().scanCount == 1L
    }

    @Test void respectsScannerSidecarLockAndRecoversOnRelease() {
        Path database = work.resolve('scans.duckdb')
        Dedup.open(database).close()
        ScannerDatabase reader = new ScannerDatabase(database.toString())
        new DatabaseLock(Path.of(database.toString() + '.lock')).withCloseable {
            ApiFailure failure = assertThrows(ApiFailure) { reader.status() }
            assert failure.code == 'DATABASE_LOCKED'
            assert failure.status.value() == 423
        }
        assert reader.status().supported
    }

    @Test void neverInitializesUnknownOrMissingDatabases() {
        Path missing = work.resolve('missing.duckdb')
        ApiFailure notFound = assertThrows(ApiFailure) { new ScannerDatabase(missing.toString()).status() }
        assert notFound.code == 'DATABASE_NOT_FOUND'
        assert !Files.exists(missing)

        Path unrelated = work.resolve('unrelated.duckdb')
        DriverManager.getConnection('jdbc:duckdb:' + unrelated).withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute('CREATE TABLE unrelated (x INT)') }
        }
        ApiFailure invalid = assertThrows(ApiFailure) { new ScannerDatabase(unrelated.toString()).status() }
        assert invalid.code == 'UNSUPPORTED_SCHEMA'
        DriverManager.getConnection('jdbc:duckdb:' + unrelated).withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('SELECT count(*) FROM unrelated').withCloseable { assert it.next() }
            }
        }
    }

    @Test void rejectsUnknownSchemaVersionWithoutMigration() {
        Path database = work.resolve('future.duckdb')
        Dedup.open(database).close()
        DriverManager.getConnection('jdbc:duckdb:' + database).withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute('UPDATE schema_info SET version=99') }
        }
        ApiFailure invalid = assertThrows(ApiFailure) { new ScannerDatabase(database.toString()).status() }
        assert invalid.code == 'UNSUPPORTED_SCHEMA'
    }

    @Test void detectsCompleteOptionalSchemasAndRejectsPartialFeatures() {
        Path complete = work.resolve('features.duckdb')
        Dedup.open(complete).close()
        DriverManager.getConnection('jdbc:duckdb:' + complete).withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                ['/archive/schema.sql', '/image/schema.sql'].each { resource ->
                    getClass().getResourceAsStream(resource).withCloseable { stream ->
                        stream.getText('UTF-8').split(';').findAll { it.trim() }.each { statement.execute(it) }
                    }
                }
                statement.execute("INSERT INTO archive_schema_info VALUES (1, 'fixture')")
                statement.execute("INSERT INTO image_schema_info VALUES (1, 'fixture')")
            }
        }
        Map status = new ScannerDatabase(complete.toString()).status()
        assert status.archiveSchema == 1
        assert status.imageSchema == 1

        Path partial = work.resolve('partial.duckdb')
        Dedup.open(partial).close()
        DriverManager.getConnection('jdbc:duckdb:' + partial).withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute('CREATE TABLE archive_jobs (x INT)') }
        }
        ApiFailure invalid = assertThrows(ApiFailure) { new ScannerDatabase(partial.toString()).status() }
        assert invalid.code == 'UNSUPPORTED_SCHEMA'
    }
}
