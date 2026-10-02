# Whole-database merge

## Purpose and invocation

`merge-database.groovy` imports **all scans** from one existing fnord-dedup2 DuckDB database into another existing database. It transfers the filesystem inventory, saved hashes and errors, archive results and nested relationships, and disk-image results. It does not scan, extract, open VM images, repair data, or touch the files referenced by a scan. The source can describe storage that is currently offline.

Build with Java 21:

```bash
./gradlew test installDist
```

From the repository checkout, validate first:

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy \
  scripts/merge-database.groovy \
  --source /data/source.duckdb \
  --destination /data/master.duckdb \
  --dry-run
```

Remove `--dry-run` to import:

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy \
  scripts/merge-database.groovy \
  --source /data/source.duckdb \
  --destination /data/master.duckdb
```

The script is also packaged in the installed distribution. From its root, use `bin/fnord-dedup2-groovy scripts/merge-database.groovy ...`. It needs the bundled Groovy/DuckDB libraries and Java, **not** Python, libarchive, QEMU, or libguestfs. Python is used only by the process test harness.

Both database files must already exist and be nonempty regular files created by this application. Missing files, unrelated databases, same-file paths including symlink/hardlink aliases, and conflicting database control paths are rejected. The destination is never silently created. Keep a backup of important destination data before administrative operations. Run against stable local database files, not concurrently copied or externally rewritten files.

## Names and identifiers

Any exact scan-name overlap refuses the **entire** import before destination mutation. Names retain their existing case-sensitive database semantics: `Photos` and `photos` are different names. No names are renamed, overwritten, coalesced, or silently skipped. A diagnostic reports the total conflict count and up to 1,000 names so output remains bounded. Equal roots with different names are allowed.

Every imported scan gets a fresh destination ID. Allocation starts above the destination's maximum scan ID and follows ascending source scan ID order. A candidate equal to that scan's old ID is skipped so every imported ID actually changes, even with an empty destination. Allocation uses checked BIGINT arithmetic and may leave gaps. All scan-scoped references use one mapping. Entry IDs, parent IDs, directory checkpoints and `next_entry_id` stay scan-local and are preserved.

Every imported archive result and image result receives a fresh UUID in its own domain. All relational references to those results are translated consistently. Two source scans sharing one canonical result still share one imported result; nested archive relationships remain a DAG. Existing destination results are never coalesced with imported results, even when content fingerprints match. Valid finalized historical results are copied as well as currently reachable results. Existing diagnostic text and opaque summaries are preserved verbatim; an old UUID appearing inside free-text diagnostics is historical text, not a rewritten relational link.

Hashes, scan names, root paths, timestamps, member ordinals, filesystem IDs, component topology, provider/policy identities and partial-result diagnostics are retained. Source file paths do not have to exist. The merge does not rehash source files or certify the historical truth of a recorded checksum.

## Optional features and ownership

All combinations of basic-only, archive, image, and archive-plus-image databases are supported when their schemas pass validation. Missing optional destination tables and their standard indexes are initialized inside the same import transaction. Existing destination feature instance IDs are retained; newly initialized features receive new IDs.

Source `archive_temp_roots` and `image_temp_roots` records are **never copied**. Source feature instance IDs are never transplanted. The destination must not acquire permission to clean another database's temporary storage. Existing destination registrations remain untouched. No archive/image helper or cleanup API is invoked by merge.

## Validation and transaction boundaries

The service acquires the application's exclusive sidecar locks for **both** canonical database paths in deterministic order. Other fnord-dedup2 operations, including reports, must close their database sessions first. Native DuckDB locks are also respected. Sidecar lock files are retained after release, following the normal application lock protocol.

Preflight attaches both databases explicitly read-only. It compares each feature's version, native-normalized table definitions, columns, constraints, defaults and indexes against the bundled supported schemas. Unknown tables and persisted views, macros/functions, sequences, custom types or schemas are refused rather than silently omitted. This is deliberately stricter than a generic DuckDB import: user-added schema objects are unsupported. A future schema version needs an updated merger.

Logical audits cover scan IDs/names/phases, inventory parent and path identity, directory queues and checkpoints, saved hash representation, archive and image provenance, canonical references, result/member/filesystem uniqueness, archive graph ordering, image component graphs and member integrity. Both source and destination must pass. Native archive errors may legitimately refer to an un-emitted failed header. Image source-change/native-failure diagnostics can outlive discarded guest rows; those existing documented cases are accepted rather than misclassified as corruption.

After preflight, only destination is reopened writable, while both application locks and the source read-only attachment remain held. Destination is re-audited. One transaction contains missing-feature initialization, all inserts and final verification. Copying uses explicit-column `INSERT INTO ... SELECT ...` joins against temporary ID maps, not one Groovy object or transaction per file.

Before commit, every copied table's imported rows are counted through its ID mapping and compared to the transformed source in both directions with `EXCEPT ALL`. This is an exact multiset check, not merely a row-count or aggregate-hash comparison. The destination receives a full post-import consistency audit. Any precommit failure rolls back the entire import, including optional-schema initialization. The source is never opened for writing.

The audits cover the application's implemented v1 invariants; they are not forensic recovery, a proof against every native database-parser defect, or protection against a hostile process replacing trusted files behind application locks. Do not use the utility as an untrusted-database sandbox.

## Interrupted source scans and WAL

Structurally valid paused filesystem discovery or hashing can be imported with its checkpoint and pending work intact. A later normal `resume` uses the original stored root path. Finalized archive/image results with errors and archive `SKIPPED` results may be imported. Pending feature jobs are allowed where their state is coherent.

**RUNNING archive/image jobs or result generations in either database cause refusal.** The merge does not execute recovery against the source or promote staging rows. Complete normal feature recovery/resume in the originating application first, close it, then retry the merge. Unknown and inconsistent states are refused, not repaired.

Keep each database together with its WAL. A committed source WAL is read only if DuckDB can expose it that way. When read-only opening requires unsupported recovery, the merger refuses and requests clean closure/recovery by the originating application. It never deletes or rewrites a source WAL. Do not manually remove a WAL to make an import proceed.

## Cancellation and recovery

Ctrl+C/SIGTERM requests cancellation. A small watcher can cancel an active JDBC statement while the owning thread handles rollback and closure. The shutdown hook waits up to 30 seconds; uninterruptible operating-system I/O is not guaranteed to stop promptly.

Before COMMIT, cancellation or an exception must leave no imported database state. SIGKILL relies on DuckDB transaction recovery; reopening destination must reveal either the old committed database or the fully committed import, never a partly imported set of tables.

COMMIT is the publication point. Cancellation arriving after it cannot undo the import. Output/reporting or cleanup failure after a confirmed commit is explicitly reported with `database_committed: true`, not described as a rollback. A process killed immediately after commit might not have printed its success report: inspect destination scan names before retrying. A repeat import normally refuses because those names already exist.

Private `fnord-merge-*` directories under the host JVM temporary directory hold disposable DuckDB spill data. Normal cleanup removes only the exact newly created directory, without following links. SIGKILL can leave scratch data; this version has no automatic stale-merge-directory cleaner. It does not scan or clean arbitrary existing temporary directories. Never manually delete a work directory still used by a running process.

Transaction rollback preserves logical destination state; database/WAL physical layout can change after a writable open or crash recovery. The clean-file dry-run/refusal tests additionally verify byte-unchanged database files, and successful-import tests verify byte-unchanged source files.

## Output and controls

The CLI emits a single JSON object on stdout. Progress and refusals go to stderr. Successful status is `IMPORTED`; a valid empty source returns `NO_SCANS` and changes nothing. A successful nonempty dry-run returns `READY`. The report contains source/destination paths, per-table row counts, features to initialize, timestamp and a streamed `scan_id_map` array such as:

```json
[
  {"old_scan_id": 1, "new_scan_id": 8, "name": "Photos"},
  {"old_scan_id": 4, "new_scan_id": 9, "name": "Servers"}
]
```

The example is the mapping field, not the complete report. Dry-run IDs are proposals, not reservations; the real import always obtains both locks and revalidates a fresh plan.

| Option | Default | Purpose |
| --- | --- | --- |
| `--source` | Required | Existing database to read. |
| `--destination` | Required | Existing database to extend. |
| `--dry-run` | Off | Full preflight and proposed mappings without persistent database changes. |
| `--quiet` | Off | Suppress progress, not errors. |
| `--verbose` | Off | Include troubleshooting stack traces. |
| `--memory-limit` | `1GB` | DuckDB memory budget; separate from the JVM heap. |
| `--database-threads` | `2` | DuckDB worker threads. |

Save stdout with shell redirection to a **new report path that is not either database or a sidecar**. There is no `--report` option. An external shell can truncate its redirection target before the script starts, so the script cannot protect a database accidentally used as the report target.

Exit codes: 0 success/dry-run/no-op; 1 execution, open or postcommit reporting/cleanup failure; 2 argument usage; 3 name, path, lock or schema refusal; 4 logical consistency failure; 130 cooperative cancellation. Unix signals can produce their usual signal-derived status. Inspect `database_committed` for postcommit errors.

SQL audits, sorting and exact multiset verification may spill. The utility bounds Groovy pages to 1,024 IDs and streams report mappings, but total transaction/WAL, DuckDB memory and disk costs depend on data. Ensure sufficient destination and temporary-disk space. There is no exact required-space estimator, hard whole-process memory limit, or measured throughput claim. No unsafe durability mode or per-table commit is offered.

## Groovy API

The thin script uses reusable `DatabaseMerger`, `DatabaseAuditor`, `MergeSql` and `MergeCommand` classes. Do not open the same databases through `Dedup` while merging; the service owns both locks and its private JDBC connection.

```groovy
import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.merge.DatabaseMerger
import groovy.json.JsonOutput
import java.nio.file.Path

StopToken stop = new StopToken()
def merger = new DatabaseMerger(
    new ScanOptions(memoryLimit: '1GB', databaseThreads: 2), stop)
merger.progress = { event -> System.err.println(JsonOutput.toJson(event)) }

merger.merge(Path.of('/data/source.duckdb'), Path.of('/data/master.duckdb'), false) {
    summary, streamScanMappings ->
    println JsonOutput.toJson(summary)
    streamScanMappings { row -> println JsonOutput.toJson(row) }
}
```

The API example intentionally emits a summary followed by mapping rows; the CLI assembles them into one JSON object. Operations and callbacks are synchronous. Consume the mapping stream inside the callback, do not retain it or invoke reentrant JDBC work. Only `StopToken.cancel()` is intended for another thread. Without a report callback, `merge` returns its bounded summary map.

## Verification and scope

```bash
./gradlew test installDist distTar
python3 scripts/database-merge-smoke.py
```

`DatabaseMergeTest` exercises all 16 source/destination optional-feature combinations, name conflicts, identical file aliases, scan/result-ID collisions, shared canonical and nested results, skipped/historical/partial outcomes, paused discovery/resume, malformed persistence, checked allocation, locks, cancellation and postcommit reporting failures. The process suite uses a generated 250,000-file source, checks read-only WAL handling, tests the packaged script, kills transfers after several uncommitted tables and cancels before commit, then reopens the destination. Existing filesystem/archive tests remain required.

No selected-scan import, renaming, merge-by-name, semantic coalescing, repair, automatic stale-directory cleanup, schema migration, source refresh or permanent import ledger is implemented. Repeated overlapping imports refuse by name. Ordinary scanning, archive and image schemas and their analysis behavior are unchanged.
