# Architecture and extension contract

The optional web-api module serves the Angular production build and queries scanner DuckDB under the CLI sidecar lock. Each request opens a read-only JDBC connection, applies finite memory/thread settings, validates schema versions, returns bounded rows or aggregate counts, and closes the connection. Concurrent requests in one web process serialize before taking the exclusive lock. File-search requests normalize and validate every field before generating SQL; caller values are parameters, while sort/pattern fragments come only from closed enums. Keyset cursors bind to the normalized filter and sort contract. No source file is opened and no scanner table is written.

Saved-search and scenario definitions use a separate web-owned DuckDB file with its own schema and process-lifetime sidecar lock. These rows are partitioned by the canonical scanner database path. Startup configuration rejects a state path that aliases scanner data. State schema v2 transactionally migrates v1 saved searches and adds scenario definitions, manual overrides, paged groups, and decision snapshots. State schema v3 transactionally adds content signatures keyed by size and SHA-256, shared across the state database and independent of scan/path identities. Page annotations use one bounded state lookup; matching files include singletons and every duplicate copy. Signature creation derives saved evidence under the scanner lock before acquiring state access, preserving the scenario generator's lock order. Unknown state versions are rejected. The scanner connection remains read-only and never initializes web tables; see docs/WEB_UI.md.

Scenario generation holds one scanner read connection/lock while streaming selected persisted size/SHA-256 evidence into a state appender. Only the request coordinator owns either JDBC connection. State transactions execute SQL before creating an appender, then publish the completed generation and remove the previous generation atomically. Failures, occurrence caps, and query/transfer deadlines roll back the new rows and preserve the previous snapshot. The state connection applies the configured finite DuckDB memory/thread settings. Definition and manual edits increment a revision; expected revisions and generation-bound page cursors reject stale requests. A source fingerprint detects changed selected inventory during explicit validation. Decisions remain historical plans requiring later live revalidation; no source-file operation or export is implemented. See docs/SCENARIOS.md.

## Layers

`fnord.dedup.cli` maps picocli commands to the public `Dedup` API. The CLI owns signal handling and presentation. `Dedup` owns the synchronous orchestration and is also the scripting entry point. `DiscoveryEngine` owns metadata traversal. `HashEngine` owns candidate dispatch and result validation. `FileHasher` is a thread-safe digest-provider interface; the initial provider is JDK SHA-256. `DuckStore` owns the schema and parameterized analytical queries. `BulkWriter` owns primitive appender writes and transactional checkpoints. These boundaries permit alternate traversal and checksum implementations without moving CLI logic into storage or worker threads.

Hot metadata/appender and byte-reading paths use Groovy static compilation. Query assembly, API orchestration, and CLI presentation retain Groovy's dynamic convenience.

## Schema

`scans`: unique human-readable name, canonical root, durable phase, algorithm, active directory checkpoint, next internal entry ID, timestamps.

`entries`: one inventory row per path within a scan. Root has ID 1 and parent 0. Every other entry is discovered by exactly one immediate parent. The root plus relative path reconstructs its absolute path. Modification times use epoch seconds and nanosecond adjustment to avoid millisecond truncation. Application IDs are not inodes.

`directories`: work queue keyed by scan and entry ID, with immediate parent, relative path, and completion flag. Only this relatively small control table and scan identifiers have indexes/constraints.

`hashes`: completed `(scan_id, entry_id, sha256)` records. Absence is the only representation of an uncomputed checksum. There is deliberately no checksum task state machine.

`scan_errors`: diagnostics, not authoritative per-file work state. Missing hashes remain candidates. Hash diagnostics are cleared at the start of a retry attempt; discovery diagnostics are retained because completed directories are not refreshed on resume.

`schema_info`: exactly one supported schema version. Initialization refuses a nonempty unrelated database. New schema versions must explicitly implement migration or reject old files; never silently reinterpret them.

## Discovery transaction protocol

The source walk is iterative. Bounded pages of ready directories are fetched from DuckDB. A directory stream yields one child at a time; NIO reads its basic attributes without following the final symlink. The row is appended directly, and a directory also receives a queue row. A file's contents are not opened here.

A bulk transaction contains inventory rows, new directory queue rows, errors, completed directory IDs, the next entry ID and the active-directory checkpoint. Appenders are closed/flushed before updating the checkpoint and committing. There is no autocommit per file.

For a wide directory, every configured row/time checkpoint may commit a partial list of immediate children. The parent is still unfinished. At restart, the saved active parent identifies exactly which immediate-child inventory and queue rows must be removed before repeating enumeration. Its children cannot have run because queue selection requires a completed parent. Completed siblings and their subtrees are retained. IDs are monotonic and can have gaps after replay.

An uncommitted transaction is rolled back. A committed directory completion is always accompanied by its inventory. Do not mark a directory complete separately from its records. On iterator/read failure the available subset and an error are committed; this leads to `COMPLETE_WITH_ERRORS`, not a claim of full coverage. Refreshing a changed or formerly unreadable source requires a new named scan.

## Hash pipeline

After discovery, one DuckDB query computes candidate sizes with `GROUP BY size HAVING count(*) > 1`, restricts to regular files in that scan, and excludes existing completed hashes. The candidate snapshot is materialized once into a temporary table with ordinal pages. This avoids repeating a full size aggregation for every result batch or advancing with ever-increasing SQL OFFSET.

A fixed worker pool has bounded submissions. Each worker obtains a candidate's current attributes, requires a metadata match, streams a complete SHA-256, obtains the attributes again, checks the match again, and checks the number of bytes read. A result is either a completed digest, a diagnostic, or cancellation. Unexpected programming/provider failures abort the invocation rather than masquerading as a filesystem read error.

The coordinator alone appends results/errors and commits. Workers never share a JDBC connection. Cancellation stops submissions and interrupts workers; uncollected results may be discarded, since missing hashes are safe to recompute. Successful committed hashes remain available. No partial digest is stored. A retry rebuilds missing candidates; `--rehash` first atomically clears saved hashes for that scan.

Duplicate reporting groups only completed hashes by both size and digest. Reports require a terminal phase unless explicitly marked partial. Exports stream rows through the application; DuckDB may still allocate or spill for joins/grouping/sorting.

## Lifecycle and durability

`DISCOVERING -> READY -> HASHING -> COMPLETE | COMPLETE_WITH_ERRORS` is a durable work-state sequence, not a process-running flag. Interrupted phases remain resumable. Reports and other commands acquire the same process-level sidecar lock as writers. The API is single-owner except for `StopToken`.

The CLI shutdown hook asks the coordinator to stop and waits up to 30 seconds. This is a best-effort graceful path, not a promise to interrupt uninterruptible kernel/filesystem I/O. Abrupt death is handled by normal DuckDB transaction recovery plus the replay protocol. Keep the WAL with the database. We do not disable WAL, fsync, or transaction durability for speed.

Database and spill paths are canonicalized/excluded. The lock file is never unlinked after release, avoiding replacement of the locked inode by a different lock file. The original user license and source files remain untouched.

## Extension directions

A native SHA-256 provider can implement `FileHasher`, but must be thread-safe, account for all bytes, support cancellation, close resources, and never pass names through a shell. Benchmark large and small files: process startup can dominate tiny-file work. Prefer robust stdin/argument handling and never assume line-oriented filename output is safe. The initial application does not require an external checksum executable.

Adding BLAKE3 or another algorithm is not just swapping a function: persist algorithm identity per scan and adjust validation, schema compatibility, reports and tests to prevent cross-algorithm grouping. Existing version-1 provider validation deliberately accepts only SHA-256.

Other useful future improvements include a measured parallel discovery producer, a raw-byte Linux pathname representation, optional full byte-comparison verification, and an explicit metadata refresh workflow. These are not implemented features. Preserve bounded memory, single-owner writes, and atomic checkpoint invariants while extending.

## Portable filesystem paths

The persistence model distinguishes native filesystem paths from stored path identities. java.nio.file.Path is used for actual local I/O. scans.root, inventory relative paths, error relative paths and report paths use the portable stored-path codec and / separators on every host.

Canonical stored root forms are /data/... for POSIX, C:/Data/... for Windows drives, and //server/share/... for Windows UNC paths. Relative paths contain only portable / separators. Serialization joins native path components; it never globally substitutes backslashes, because Linux permits a literal backslash inside one filename component.

Before local I/O, the codec classifies the stored root and rejects roots belonging to another platform. This prevents a Windows root such as C:/Data from becoming a relative Linux path, and prevents a POSIX root from being silently mapped onto a Windows drive. Reporting and database merge do not require host interpretation of stored roots, so databases remain useful after moving between platforms when hashes are already present.

Runtime database, WAL, lock and DuckDB temporary paths remain native host paths and are not part of this stored-path contract. Archive and image processing remain Linux-only.

## References

- DuckDB JDBC bulk appender: https://duckdb.org/docs/current/clients/java/data_import
- DuckDB concurrency: https://duckdb.org/docs/current/connect/concurrency
- Groovy static compilation: https://groovy-lang.org/semantics.html#_static_compilation
- Gradle distribution checksums: https://gradle.org/release-checksums/
