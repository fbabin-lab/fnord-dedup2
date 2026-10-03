package fnord.dedup.web

import org.springframework.http.HttpStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/** Reject unfamiliar schemas; never create or migrate a scanner database. */
class SchemaInspector {
    private static final Set<String> BASE = [
        'schema_info', 'scans', 'entries', 'directories', 'hashes', 'scan_errors'
    ].asImmutable() as Set<String>
    private static final Set<String> ARCHIVE = [
        'archive_schema_info', 'archive_runs', 'archive_inputs', 'archive_jobs', 'archive_results',
        'archive_volumes', 'archive_members', 'archive_nested', 'archive_errors', 'archive_temp_roots'
    ].asImmutable() as Set<String>
    private static final Set<String> IMAGE = [
        'image_schema_info', 'image_runs', 'image_jobs', 'image_results', 'image_components',
        'image_partitions', 'image_filesystems', 'image_entries', 'image_errors', 'image_temp_roots'
    ].asImmutable() as Set<String>

    static Map<String, Object> inspect(Connection connection) {
        try {
            Set<String> tables = [] as Set<String>
            connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema='main'").withCloseable { statement ->
                statement.executeQuery().withCloseable { ResultSet result ->
                    while (result.next()) tables.add(result.getString(1))
                }
            }
            requireTables(tables, BASE, 'base')
            int base = version(connection, 'schema_info')
            Integer archive = null
            Integer image = null
            if (tables.any { it.startsWith('archive_') }) {
                requireTables(tables, ARCHIVE, 'archive')
                archive = version(connection, 'archive_schema_info')
            }
            if (tables.any { it.startsWith('image_') }) {
                requireTables(tables, IMAGE, 'image')
                image = version(connection, 'image_schema_info')
            }
            return [baseSchema: base, archiveSchema: archive, imageSchema: image, supported: true]
        } catch (SQLException error) {
            throw unsupported('Scanner schema could not be validated.', error)
        }
    }

    private static void requireTables(Set<String> actual, Set<String> expected, String feature) {
        if (!actual.containsAll(expected)) throw unsupported("Incomplete or missing ${feature} schema.")
    }

    private static int version(Connection connection, String table) {
        // The table name is one of the three fixed names above, never caller input.
        connection.prepareStatement("SELECT version FROM ${table}").withCloseable { statement ->
            statement.executeQuery().withCloseable { ResultSet result ->
                if (!result.next()) throw unsupported("Missing ${table} version.")
                int value = result.getInt(1)
                if (result.wasNull() || result.next() || value != 1) {
                    throw unsupported("Unsupported ${table} version; expected one row with version 1.")
                }
                return value
            }
        }
    }

    private static ApiFailure unsupported(String message, Throwable cause = null) {
        new ApiFailure('UNSUPPORTED_SCHEMA', HttpStatus.UNPROCESSABLE_ENTITY, message, cause)
    }
}
