# Web explorer — inventory and file search

The optional Groovy/Spring Boot service and Angular interface are companions to the CLI scanner. They open an existing scanner DuckDB file read-only, display database/schema status, summarize scans, browse recorded directories and files, search the saved inventory, and show recorded filesystem errors. Saved search definitions live in a separate web-owned DuckDB file. No web request starts a scan or reads or modifies source files.

## Build and run

Java 21 and Node.js 24 are required. From the repository root:

    ./gradlew test installDist distTar :web-api:bootJar
    java -jar web-api/build/libs/fnord-dedup2-web.jar \
      --db /data/scans.duckdb --state-db /data/fnord-web.duckdb --port 8080

Open http://127.0.0.1:8080/. The jar serves the Angular production build and /api/v1. For UI development, start the backend, then run npm ci and npm start in web-ui/; the Angular server proxies API requests to port 8080.

The scanner path is configured at startup, never supplied in a REST request. `--state-db` is optional and defaults to `~/.fnord-dedup2/web.duckdb`. It must not identify the scanner database. Scanner queries default to a 512 MB DuckDB memory limit and two database threads; override them with `--dedup.web.memory-limit=1GB` and `--dedup.web.database-threads=4`. The default bind address is 127.0.0.1. An explicit --server.address can bind another interface; this release has no built-in authentication, so protect remote access.

## Available screens

- **Dashboard:** database-wide entry, byte, hash, confirmed-group, and error counts; optional archive/image run counts when their supported schemas exist.
- **Scans:** bounded scan pages with name, phase, and error filters; inventory and hash counts.
- **Scan overview:** recorded phase, timestamps, inventory totals, pending directory work, confirmed duplicate groups, and errors. Incomplete hashes remain unresolved.
- **File Explorer:** lazy directory tree, breadcrumbs, paged children (directories first), and a file drawer with the saved SHA-256, confirmed occurrence count, and related errors.
- **File Search:** server-side filters across all scans, selected scans, or a directory; filename contains/starts/ends/exact/glob/explicit-regex modes; path, extension, byte size, modified time, entry type, hash, duplicate, and error filters; stable sorting and bounded pages. Scan and directory pages link directly into a scoped search.
- **Saved searches:** create, load, update, and delete named filter sets. Definitions are isolated by scanner database identity in the separate web-state database.
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
| POST /api/v1/search/files | Bounded, server-filtered entry page with a filter-bound keyset cursor and hash-coverage metadata. |
| GET /api/v1/saved-searches | Up to 500 saved searches belonging to the configured scanner database. |
| POST /api/v1/saved-searches | Create a saved search from `{name, description, request}`. |
| GET /api/v1/saved-searches/{id} | Read one saved search. |
| PUT /api/v1/saved-searches/{id} | Replace one saved search. |
| DELETE /api/v1/saved-searches/{id} | Delete one saved search. |

Collections return `items` and `page: {limit, nextCursor, hasMore}`. Page size is 1–500, default 100 except the UI scan list. Scan, directory, and search cursors use stable keyset order. A search cursor is cryptographically bound to its normalized filters and sort order; changing either rejects the cursor. Error rows have no schema identifier, so errors use offset pagination; pages can shift if a scanner changes the database between requests. Do not treat cursors as durable bookmarks.

The search body accepts `scanIds`, optional `directory: {scanId, entryId, recursive}`, `name: {operator, value, caseSensitive}`, path filters, `extensions`, size and modified ranges, `kinds`, `hashState`, `duplicate`, `errorState`, `sort`, `limit`, and a returned `cursor`. Duplicate counts are computed from persisted size plus SHA-256 within the selected scan scope. `NOT_CONFIRMED` includes unresolved un-hashed files; it does not claim those files are unique. Responses repeat this limitation in `coverage`.

All filter values and cursors are validated and bound as JDBC parameters. Dynamic SQL is limited to validated sort/name enums and bounded placeholder counts. The web process takes the CLI's existing `.lock` sidecar lock for each read-only scanner request, with its own requests serialized to avoid self-contention. A CLI owner returns HTTP 423 `DATABASE_LOCKED`. Unknown scanner schema versions are rejected; the web process never initializes or migrates scanner data.

The web-state database has its own schema, connection, and lifetime-held `.lock`. It stores only normalized saved-search JSON and scanner path identities. The application rejects a state path that aliases the scanner database and never writes saved-search tables into scanner data.

## Verification

InventoryServiceTest uses actual scanner-created DuckDB databases to check scan pages, directory cursors, breadcrumbs, duplicate counts, incomplete hash coverage, error pages, input validation, and CLI lock contention. FileSearchServiceTest covers search modes, scope, duplicate/hash semantics, error filters, stable bound cursors, regex validation, and parameter safety against real DuckDB data. WebStateStoreTest covers CRUD persistence, scanner isolation, lock contention, state/scanner path rejection, and unchanged scanner bytes. ScannerDatabaseTest covers read-only connections and schema rejection. The repository's process, rollback, archive, image, and Windows CI checks continue to apply.
