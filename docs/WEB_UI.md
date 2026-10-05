# Web explorer — inventory, search, duplicates, signatures, and scenarios

The optional Groovy/Spring Boot service and Angular interface are companions to the CLI scanner. They open an existing scanner DuckDB file read-only, display database/schema status, summarize scans, browse recorded directories and files, search the saved inventory, explore confirmed duplicate groups, build retention scenarios, and show recorded filesystem errors. Saved searches, content signatures, and scenario definitions, manual choices, and generated snapshots live in a separate web-owned DuckDB file. No web request starts a scan or reads or modifies source files.

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
- **Duplicate Explorer:** explicitly select one scan or several scans; view all confirmed groups in that scope or only groups spanning selected scans. Filter by minimum occurrences/scans, filename, path, extension, size, modification time, and recorded errors. Group and occurrence results use separate bounded pages. Scan overview links into a single-scan analysis; a hashed file's details link into its content group, with additional scans selectable.
- **Scenario Builder:** create a plan from selected scans, a directory, saved search, duplicate filters, or a content group. Save scope, ordered retention rules, protected paths, and manual mode. Generate explicitly, review paged KEEP/REMOVE/UNDECIDED/UNRESOLVED decisions, accumulate manual choices, regenerate, and validate the saved inventory fingerprint. Reloading a definition never triggers generation. See [the scenario guide](SCENARIOS.md).
- **Signature store:** save a regular file's size and recorded SHA-256 from File Explorer, File Search, a duplicate occurrence, or File Details. Add an optional tag and memo, edit them later, delete a signature, or browse its paged matching files. Every matching copy is a removal candidate, even a singleton. Red rows/badges signal signature matches; amber rows/badges signal confirmed duplicate candidates. Files satisfying both show both badges and edge colors.
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
| POST /api/v1/duplicates/groups | Confirmed groups in an explicit selected scan scope, aggregate totals, and hash/discovery/error coverage. |
| POST /api/v1/duplicates/groups/{groupId}/occurrences | Every occurrence of a qualifying group in the same selected scope, with filter-match flags and a separate cursor. |
| GET /api/v1/saved-searches | Up to 500 saved searches belonging to the configured scanner database. |
| POST /api/v1/saved-searches | Create a saved search from `{name, description, request}`. |
| GET /api/v1/saved-searches/{id} | Read one saved search. |
| PUT /api/v1/saved-searches/{id} | Replace one saved search. |
| DELETE /api/v1/saved-searches/{id} | Delete one saved search. |
| GET /api/v1/signatures?limit=100&cursor=... | Bounded content signatures in the Web state database. |
| POST /api/v1/signatures | Create from `{scanId, entryId, tag?, memo?}` using saved scanner evidence. |
| GET /api/v1/signatures/{id} | Read one signature. |
| PUT /api/v1/signatures/{id} | Edit `{tag?, memo?}`; size, algorithm, and digest are immutable. |
| DELETE /api/v1/signatures/{id} | Delete a signature and clear its match signals. |
| GET /api/v1/signatures/{id}/matches?limit=100&cursor=... | All matching regular-file observations across the configured database, including singletons. |
| GET /api/v1/scenarios?limit=50&cursor=... | Bounded scenario definitions belonging to the configured scanner database. |
| POST /api/v1/scenarios | Create a DRAFT definition from `{name, description, config}`. |
| GET /api/v1/scenarios/{id} | Definition, revision, status, and current snapshot metadata. |
| PUT /api/v1/scenarios/{id} | Replace a definition with an expected `revision`. |
| DELETE /api/v1/scenarios/{id}?revision=... | Delete a definition, overrides, and snapshots with an expected revision. |
| POST /api/v1/scenarios/{id}/generate | Atomically generate decisions for an expected `revision`. |
| POST /api/v1/scenarios/{id}/validate | Validate the current snapshot and selected inventory fingerprint for an expected `revision`. |
| POST /api/v1/scenarios/{id}/overrides | Save 1–100 recorded-path manual choices with an expected `revision`. |
| POST /api/v1/scenarios/{id}/overrides/reset | Reset manual choices with an expected `revision`. |
| GET /api/v1/scenarios/{id}/groups?limit=100&cursor=... | Bounded generated content groups and decision totals. |
| GET /api/v1/scenarios/{id}/groups/{groupId}/decisions?limit=100&cursor=... | Bounded observations, effective decisions, reasons, and pending manual choices. |

Paged collections return `items` and `page: {limit, nextCursor, hasMore}`. Page size is 1–500, default 100 except the UI scan list and scenario definitions (1–100, default 50). Scan, directory, search, and scenario cursors use stable keyset order. A search cursor is cryptographically bound to its normalized filters and sort order; changing either rejects the cursor. Scenario pages bind to scenario, revision, generation, and endpoint; decision pages also bind to the content group. Error rows have no schema identifier, so errors use offset pagination; pages can shift if a scanner changes the database between requests. Do not treat cursors as durable bookmarks.

The search body accepts `scanIds`, optional `directory: {scanId, entryId, recursive}`, `name: {operator, value, caseSensitive}`, path filters, `extensions`, size and modified ranges, `kinds`, `hashState`, `duplicate`, `errorState`, `sort`, `limit`, and a returned `cursor`. Duplicate counts are computed from persisted size plus SHA-256 within the selected scan scope. `NOT_CONFIRMED` includes unresolved un-hashed files; it does not claim those files are unique. Responses repeat this limitation in `coverage`.

All filter values and cursors are validated and bound as JDBC parameters. Dynamic SQL is limited to validated sort/name enums and bounded placeholder counts. The web process takes the CLI's existing `.lock` sidecar lock for each read-only scanner request, with its own requests serialized to avoid self-contention. A CLI owner returns HTTP 423 `DATABASE_LOCKED`. Unknown scanner schema versions are rejected; the web process never initializes or migrates scanner data.

The web-state database has its own schema, connection, and lifetime-held `.lock`. Saved searches, scenario definitions, manual choices, and generated decision snapshots are partitioned by scanner path identity. Content signatures are shared across this state database, so they also work after selecting a different scanner database or adding future scans. Schema v3 adds signatures through a transactional v2 migration; v1 first follows the existing v2 migration. Saved searches and scenarios are preserved. Unknown versions are rejected. The application rejects a state path that aliases the scanner database and never writes web tables into scanner data.

## Content signatures and removal signals

A signature uniquely identifies `(SHA-256, size, sha256)`, with an optional tag (up to 120 characters) and memo (up to 4,000 characters). Size is an exact decimal string in responses, including values beyond JavaScript's safe integer range. Creating the same signature again returns HTTP 409 `SIGNATURE_EXISTS`; select **Edit signature** to update its notes. The server derives size and digest from the selected saved regular file and rejects client-supplied content identities, non-files, incompatible algorithms, missing hashes, and invalid saved evidence.

File details, directory children, search results, duplicate groups, and duplicate occurrences return `signature`, `signatureMatch`, `duplicateCandidate`, and `removalCandidate`. `removalCandidate` is true for every signature match, regardless of duplicate counts or which other copies are visible. `duplicateCandidate` describes confirmed duplicates in the endpoint's existing scope: database-wide for File Explorer/details, selected scans for search and Duplicate Explorer. A bounded state lookup annotates only the returned page; the application never loads the entire signature store or inventory into memory.

Selecting **Find matches** lists every matching saved regular file across all scans in the configured scanner database. This includes renamed files and singletons. Match cursors bind to the signature and order by scan and entry ID. Rows with missing hashes remain unresolved; matching size alone is insufficient. To save hashes for unique-size files, run:

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb hash --name "scan-name" --hash-complete
```

Then refresh the WebUI. Saving, editing, or deleting a signature immediately updates currently visible matching rows and File Details. A refresh reloads changes made by another client. These are historical removal signals; they do not execute deletion or modify an existing Scenario Builder snapshot. Scenario retention and last-keeper validation continue to describe their separately saved plans.

## Duplicate groups and scope

`POST /api/v1/duplicates/groups` requires `scanIds` (one to 1,000 IDs). No selection never means all scans. `mode: "ANY"` includes any confirmed group within the selected inventories; `mode: "ACROSS_SCANS"` requires at least two selected scans and returns only groups spanning at least two of them. `minOccurrences` defaults to 2 and `minScans` to 1. Each group requires identical persisted size and SHA-256 among regular filesystem entries. Archive members and image guest files are outside this view.

Filename, path, extension, size, modification-time, and error filters use the file-search representations. An optional `directory: {scanId, entryId, recursive}` restricts matching observations to that recorded directory. They select a group when at least one occurrence satisfies all those filters. Group counts always cover **all** occurrences in the selected scans. The occurrence endpoint returns those full groups with `matchesFilters` flags, so a filename or directory filter cannot hide another copy. An optional `entry: {scanId, entryId}` restricts groups to that recorded file's content; its scan must be selected, and a missing hash returns `HASH_UNAVAILABLE`, not an empty uniqueness claim.

Group sort fields are `SIZE`, `OCCURRENCES`, `SCANS`, and `OBSERVED_BYTES`, with `ASC` or `DESC` direction. The default is size descending. Stable ties use size and digest. Occurrences order by scan ID, relative path, and entry ID. Both endpoints accept `limit` (1–500) and return independent keyset cursors bound to the normalized scope and filters; occurrence cursors also bind to the group ID. Supply the same request body when paging or opening an occurrence page. Group IDs use `size:sha256` and identify content, not a permanent selected-scope result. Counts may change after a CLI run; cursors are transient.

Group responses include `summary` across all qualifying groups, not just the page, plus selected-scope coverage (`files`, `hashedFiles`, `unhashedFiles`, `incompleteScans`, and `scanErrors`). Byte totals use decimal strings and wide arithmetic. `observedBytes` is size multiplied by recorded occurrences. It is **not** an estimate of reclaimable space: overlapping scans and repeated historical observations can represent the same physical file. The file drawer's occurrence count is explicitly labeled as database-wide; group counts use only the selected scans. Scenario Builder uses this same content evidence for saved planning decisions; export and execution are later work.

This view never runs CLI cross-scan verification or calculates missing hashes. Use the CLI's explicit hashing or cross-scan command, then refresh the analysis. The CLI's exclusive lock still applies. Saved hashes work with offline or foreign-platform roots because the UI only joins portable stored paths.

## Verification

After building the distribution and web jar, `node web-ui/scripts/duplicates-smoke.mjs --http-only` checks the packaged duplicate, signature, and scenario APIs against generated scanner fixtures and verifies unchanged scanner bytes. For full browser checks, run `npx --no-install playwright install --with-deps chromium` from `web-ui/`, then run the script without `--http-only`. Linux CI covers scan selection, filters, occurrence pagination, cross-scan file-detail links, same-component route changes, scenario save/generate/reload, manual choices, last-keeper validation, retention rules, directory scope, signature creation/editing, singleton and all-copy match signals, signature deletion, and mobile width; it uploads desktop/mobile screenshots. Browser dependencies are development-only and do not enter the production Angular bundle.

InventoryServiceTest uses actual scanner-created DuckDB databases to check scan pages, directory cursors, breadcrumbs, duplicate counts, incomplete hash coverage, error pages, input validation, and CLI lock contention. FileSearchServiceTest covers search modes, scope, duplicate/hash semantics, error filters, stable bound cursors, regex validation, and parameter safety against real DuckDB data. DuplicateServiceTest covers scope isolation, cross-scan groups, equal-size different hashes, zero-byte groups, group-selection filter semantics, both cursor types, reference files, unresolved hashes, exact large byte totals, offline/foreign roots, scanner locks, and unchanged scanner bytes. ScenarioServiceTest covers state migration, revision conflicts, ordered rules, directory targets, protected aliases, manual choices, last-keeper validation, unresolved history, source changes, cursor binding, occurrence rollback, actual native query timeout, nanosecond ordering, and exact byte totals. WebStateStoreTest covers CRUD persistence, scanner isolation, lock contention, state/scanner path rejection, and unchanged scanner bytes. SignatureServiceTest covers singleton and all-copy matches, persisted notes, future scans, state migration from v2, missing hashes, invalid inputs, immutable content identity, bound match cursors, and unchanged scanner/source bytes. ScannerDatabaseTest covers read-only connections and schema rejection. The repository's process, rollback, archive, image, and Windows CI checks continue to apply.
