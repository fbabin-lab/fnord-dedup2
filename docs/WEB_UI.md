# Web explorer — foundation milestone

The optional web application is a read-only companion to the existing scanner. This first milestone opens a configured scanner DuckDB file, verifies its schema, and reports its status in an Angular shell. The scan explorer, search, duplicate groups, scenarios, and cleanup manifests are future milestones. The navigation marks these future sections as inactive.

## Build and run

Java 21 and a supported Node.js 24 release are required. Build from the repository root:

```bash
./gradlew test installDist distTar :web-api:bootJar
java -jar web-api/build/libs/fnord-dedup2-web.jar \
  --db /data/scans.duckdb --port 8080
```

Open `http://127.0.0.1:8080/`. The Spring Boot jar serves both the Angular production build and `/api/v1` endpoints. To develop the UI separately, start the backend and then run `npm ci && npm start` in `web-ui/`; the Angular development server proxies `/api` to port 8080.

The default bind address is `127.0.0.1`. An explicit `--server.address=...` allows another interface and logs a warning because this milestone has no built-in authentication. Do not expose it to untrusted networks without access control.

The scanner database must already exist. The web process never initializes, migrates, or writes it. The configured path is a backend startup property, never a public REST path parameter. No web state database is created in this milestone.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/database` | Display the configured database path and read-only intent without opening it. |
| `GET /api/v1/database/status` | Return the canonical path, supported schema versions, scan count, and last database modification time. |

Every status request takes the scanner's existing `.lock` file lock, opens a DuckDB JDBC connection with `duckdb.read_only=true`, performs bounded queries, closes the connection, and releases the lock. A CLI owner produces HTTP `423` with `DATABASE_LOCKED`; the UI offers Retry. Missing databases produce `404`, missing configuration `400`, and unsupported schemas `422`. Optional archive/image schema versions are reported only when the matching feature tables exist and validate.

This lock deliberately prevents reading while a CLI process owns the database, including while it is updating the WAL. The web process does not create a connection pool against the scanner database. The `.lock` sidecar is created if absent and remains after release, matching the CLI behavior.

## Verification

`web-api` tests use real scanner-created databases to verify a read-only connection, lock contention/recovery, missing and unrelated database refusal, and future schema version refusal. The repository's standard scanner tests and process, rollback, and archive smoke tests continue to apply. The UI is built as part of the Gradle web module and embedded in its executable jar.
