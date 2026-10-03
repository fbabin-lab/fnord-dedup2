# Web explorer — scan inventory milestone

The optional Groovy/Spring Boot service and Angular interface are read-only companions to the CLI scanner. They open an existing scanner DuckDB file, display database/schema status, summarize scans, browse directories and files, and show recorded filesystem errors. Search, duplicate-group navigation, dedup scenarios, cleanup manifests, and archive/image explorers remain later milestones. No web request starts a scan or reads or modifies source files.

## Build and run

Java 21 and Node.js 24 are required. From the repository root:

    ./gradlew test installDist distTar :web-api:bootJar
    java -jar web-api/build/libs/fnord-dedup2-web.jar --db /data/scans.duckdb --port 8080

Open http://127.0.0.1:8080/. The jar serves the Angular production build and /api/v1. For UI development, start the backend, then run npm ci and npm start in web-ui/; the Angular server proxies API requests to port 8080.

The backend path is configured at startup, never supplied in a REST request. The default bind address is 127.0.0.1. An explicit --server.address can bind another interface; this release has no built-in authentication, so protect remote access.

## Available screens

- **Dashboard:** database-wide entry, byte, hash, confirmed-group, and error counts; optional archive/image run counts when their supported schemas exist.
- **Scans:** bounded scan pages with name, phase, and error filters; inventory and hash counts.
- **Scan overview:** recorded phase, timestamps, inventory totals, pending directory work, confirmed duplicate groups, and errors. Incomplete hashes remain unresolved.
- **File Explorer:** lazy directory tree, breadcrumbs, paged children (directories first), and a file drawer with the saved SHA-256, confirmed occurrence count, and related errors.
- **Errors:** paged filesystem diagnostics for a scan.

All values are historical scanner observations. The UI never verifies whether a source path still exists. A confirmed group requires persisted size and SHA-256; un-hashed files are not called unique.

## API

| Endpoint | Result |
|---|---|
| GET /api/v1/database | Configured path and read-only intent without opening the database. |
| GET /api/v1/database/status | Validated schema versions, scan count, and database timestamp. |
| GET /api/v1/dashboard | Aggregate inventory and diagnostic counts. |
| GET /api/v1/scans?limit=25&after=...&name=...&phase=...&hasErrors=true | Bounded scan page and summary columns. |
| GET /api/v1/scans/{scanId} | Scan metadata and detailed summary. |
| GET /api/v1/scans/{scanId}/summary | Same detailed summary. |
| GET /api/v1/scans/{scanId}/entries/{entryId} | Recorded entry, hash, confirmed occurrence count, and up to 20 related errors. |
| GET /api/v1/scans/{scanId}/entries/{entryId}/children?limit=100&cursor=... | Paged immediate children; no full directory tree is transferred. |
| GET /api/v1/scans/{scanId}/entries/{entryId}/breadcrumbs | Bounded ancestry for navigation. |
| GET /api/v1/scans/{scanId}/errors?limit=100&cursor=... | Bounded scan-error page. |

Collections return items and page: { limit, nextCursor, hasMore }. Page size is 1–500, default 100 except the UI scan list. Scan and directory cursors use stable keyset order. Error rows have no schema identifier, so errors use offset pagination; pages can shift if a scanner changes the database between requests. Do not treat cursors as durable bookmarks.

All filter values and cursors are validated and bound as JDBC parameters. The web process takes the CLI's existing .lock sidecar lock for each read-only request, with its own requests serialized to avoid self-contention. A CLI owner returns HTTP 423 DATABASE_LOCKED. The UI offers Refresh. Unknown schema versions are rejected; the web process never initializes or migrates scanner data.

## Verification

InventoryServiceTest uses actual scanner-created DuckDB databases to check scan pages, directory cursors, breadcrumbs, duplicate counts, incomplete hash coverage, error pages, input validation, and CLI lock contention. ScannerDatabaseTest covers read-only connections and schema rejection. The repository's process, rollback, archive, image, and Windows CI checks continue to apply.
