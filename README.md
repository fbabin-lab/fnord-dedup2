# fnord-dedup2

A Linux duplicate-file scanner written in **Groovy**, usable from the **CLI** or as a **synchronous scripting library**. Scans are stored in an embedded **DuckDB** database. No database server, web service, or installed Groovy runtime is required to run the built application.

The application is read-only with respect to source files: it never deletes, moves, links, or rewrites them.

## Build

Requires **JDK 21**, Bash, curl, unzip, sha256sum and flock. On Ubuntu 24.04:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk curl unzip util-linux python3

git clone https://github.com/fbabin-lab/fnord-dedup2.git
cd fnord-dedup2
./gradlew test installDist
./bin/fnord-dedup2 --help
```

`gradlew` is a small Linux-only bootstrap, not the generated Gradle JAR wrapper. It downloads a pinned Gradle distribution, verifies its hard-coded SHA-256, and caches it under `~/.gradle/fnord-bootstrap`. The first build needs Internet access. Application dependencies are pinned in `build.gradle`: Groovy 4.0.33, DuckDB JDBC 1.5.6.0, and picocli 4.7.7.

The installed application is in `build/install/fnord-dedup2/`. The repository helper `./bin/fnord-dedup2` directly launches that installation without running Gradle. After source changes, run `./gradlew test installDist` again.

To create a portable distribution:

```bash
./gradlew distTar
# build/distributions/fnord-dedup2-0.1.0.tar
```

Extract the entire distribution together; its `bin/` scripts use the bundled `lib/` JARs. A compatible Java 21 runtime is still required. Linux x86-64 is the CI-tested target; other architectures are not claimed as tested.

## Start a scan

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb scan \
  --name "Photos October" --root /photos
```

Each name is unique within that database. Different scans are independent inventories, not incremental updates or cross-scan comparisons. Start another scan in the same database with a different name:

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb scan \
  --name "Documents October" --root /documents
```

Global options can appear before or after the subcommand. Quote names and paths containing whitespace.

## Two distinct passes

**Discovery** enumerates all directories and entries without opening regular-file contents. It records relative path, filename, type, size, and modification time. The scan root is stored once as a canonical absolute path. Modification time is stored losslessly as epoch seconds plus nanoseconds, at the precision supplied by the filesystem. No inode, permissions/mode, UID/GID, allocated blocks, or link counts are stored. Internal entry IDs are application identifiers, not filesystem IDs.

**Hashing** begins only after discovery finishes. DuckDB identifies sizes shared by at least two regular files in that scan. Only these candidates are read and hashed with full-file SHA-256. Identical size **and** SHA-256 form a duplicate group. Empty files are supported; directories, symbolic links, FIFOs, sockets and device nodes are never hashed.

Run the phases separately:

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb scan \
  --name "Inventory only" --root /archive --discover-only

./bin/fnord-dedup2 --db /data/scans.duckdb hash \
  --name "Inventory only"
```

## Stop and resume

Press **Ctrl+C**, or send `SIGTERM` to the application process. Then resume with the same database and name:

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb resume --name "Photos October"
```

No root argument is needed on resume: the original root is persisted. Runtime tuning options can change between invocations.

Discovery commits bounded chunks, together with a durable directory queue and checkpoint. If interrupted inside a directory, its partial immediate-child inventory is removed and that directory is enumerated again; previously completed directories are retained. Children of an unfinished parent cannot be processed. This supports a directory containing millions of entries without collecting all names in JVM memory.

Hashing stores **only completed checksums**. There are no durable per-file pending/running/failed checksum statuses. On resume, committed hashes are reused and remaining candidates are recalculated. In-flight or uncommitted hashes can be lost and recomputed, intentionally.

DuckDB transactions and WAL are left enabled. `SIGKILL` bypasses graceful shutdown, but committed database work is recovered and unfinished work is replayed on the next invocation. Keep the `.duckdb` file and any adjacent `.wal` together after a crash. Do not delete a WAL or copy a live database. This is not a guarantee against storage corruption, a filesystem that violates durability, or a broken disk.

A source tree should remain reasonably stable across pauses. **Resume is not a refresh.** It does not revisit completed directories to find newly added files. Create a new named scan to obtain a new inventory. `hash --rehash` discards saved hashes for the existing inventory; it does not rediscover paths.

## Inspect results

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb list
./bin/fnord-dedup2 --db /data/scans.duckdb status --name "Photos October"
./bin/fnord-dedup2 --db /data/scans.duckdb duplicates --name "Photos October"

# Streaming, machine-readable output: one JSON object per matching file.
./bin/fnord-dedup2 --db /data/scans.duckdb duplicates \
  --name "Photos October" --format jsonl > duplicates.jsonl

./bin/fnord-dedup2 --db /data/scans.duckdb errors --name "Photos October"
```

`list`, `status`, and final work-command summaries are JSON. `duplicates --format jsonl` and `errors` stream JSON Lines rather than collecting all results in application memory. Default duplicate output groups files by size and checksum. File paths in text output are JSON-escaped so newlines and terminal-control characters are unambiguous.

Work-command checkpoint progress is written to **stderr**, leaving stdout suitable for scripting. `--quiet` suppresses progress. Progress counts explicitly say `*_written_this_run`: they are committed writes in this invocation, not a precomputed total or an estimate of tree completion. `status` calculates persisted totals.

Phases are `DISCOVERING`, `READY`, `HASHING`, `COMPLETE`, and `COMPLETE_WITH_ERRORS`. A phase describes the last durable work state, **not whether a process is currently alive**. A paused scan may therefore say `DISCOVERING` or `HASHING`.

Incomplete scans are not reported as complete: `duplicates` refuses unfinished scans unless `--allow-partial` is explicitly supplied. `COMPLETE_WITH_ERRORS` reports the confirmed subset with a warning; inspect `errors` before relying on coverage.

Exit codes: `0` success; `1` execution/configuration failure; `2` command-line syntax/usage error; `3` a work command finished with recorded scan errors; `130` cooperative cancellation. OS-delivered shutdown can return the conventional signal status instead, such as `143` for SIGTERM. Finding duplicates is not itself an error.

## Performance controls

```bash
./bin/fnord-dedup2 --db /data/scans.duckdb scan \
  --name "SSD scan" --root /archive \
  --workers 4 --batch-size 32768 --memory-limit 2GB --database-threads 4
```

| Option | Default | Purpose |
| --- | ---: | --- |
| `--batch-size` | 16384 | Maximum appender rows per transaction and candidates per hash page |
| `--directory-batch-size` | 128 | Directories processed in a work page |
| `--commit-interval-ms` | 2000 | Commit pending results periodically when the coordinator can make progress |
| `--workers` | 1 | Concurrent full-file hashing workers |
| `--buffer-kib` | 1024 | Reused read buffer per hashing worker |
| `--database-threads` | 2 | DuckDB analytical query threads |
| `--memory-limit` | 1GB | DuckDB native memory setting, separate from JVM heap |

Use one hash worker as a starting point on a spinning disk; test a small number such as 2–4 on an SSD. More workers are not automatically faster. The initial implementation uses JDK SHA-256 rather than starting a native checksum process for every file. A thread-safe `FileHasher` provider is the extension point for a different SHA-256 implementation.

Metadata and hash results use DuckDB's bulk **Appender**, not one SQL insert/commit per file. Small directories share transactions; large directories are checkpointed incrementally. Size candidates are materialized once into a DuckDB temporary table, then paged by ordinal. Workers have at most twice the worker count of submitted tasks; they never write to JDBC. Large inventory/hash tables do not have per-file indexes.

No entire-tree in-memory inventory is required. The JVM defaults to a 512 MiB maximum heap. DuckDB's native allocations, spill files, filesystem caching, and the JVM are separate resource consumers: `--memory-limit` is not a whole-process RSS cap. Increase tuning settings only with enough RAM and disk space. The commit interval is cooperative, not a hard deadline if filesystem I/O blocks.

There is no universal speedup claim or hardware benchmark yet. To measure your storage, time a `--discover-only` scan and its subsequent `hash` command separately, then compare new named scans with different worker/batch settings. Distinguish cold and warm filesystem cache runs.

## Scripting API

Use the Groovy runner included in the installed distribution; no separate Groovy installation or `@Grab` is needed:

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy \
  examples/scan.groovy /data/scans.duckdb "Scripted scan" /archive
```

```groovy
import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import java.nio.file.Path

Dedup.open(Path.of('/data/scans.duckdb'), new ScanOptions(workers: 2)).withCloseable { d ->
    d.scan('Scripted scan', Path.of('/archive'))
    d.eachDuplicate('Scripted scan') { row ->
        println "${row.sha256} ${row.path}"
    }
}
```

Other public operations are `createScan`, `discover`, `hash`, `resume`, `status`, `listScans`, `eachDuplicate`, and `eachError`. Assign `progress` to receive committed-checkpoint maps. Supply a `StopToken`, and call `cancel()` from another thread or a progress callback to pause. See `examples/resume.groovy`. All operations are synchronous. One thread owns a `Dedup` instance; only the cancellation token is intended for cross-thread use.

## Correctness boundaries

A file must still be regular, have the discovered size and modification time before and after hashing, and yield exactly the expected number of bytes. A mismatch is an error, not a duplicate. Failed hashes are retried on a later `hash`/`resume` invocation. Directory read failures are retained as discovery errors; create a new named scan after fixing access or changing the tree.

This is a **scan-time, hash-based result**, not an atomic filesystem snapshot or a byte-for-byte deletion authorization. Files may change after hashing. Metadata checks cannot detect every same-size edit whose timestamp is preserved or restored; digest collisions are also not a mathematical impossibility. Use a quiescent source or a filesystem snapshot for stronger consistency. There is no destructive deduplication feature.

Symlinks are recorded but not followed; a symlink supplied as the root is resolved once when creating the scan. This is intended for trusted directory trees, not as a security boundary against hostile concurrent path replacement. Hard-linked names are treated as separate paths because inode/link metadata is deliberately not collected; reported duplicates are not a measure of physically reclaimable disk space.

UTF-8 names, including spaces, quotes, newlines, tabs and leading dashes, are handled. Linux names containing bytes that cannot round-trip through Java UTF-8 paths are skipped with an error rather than silently stored under a corrupted name.

One application process at a time may access a database, including report commands; a lock fails fast rather than queuing. Pause/close the active process before reporting from another process. The `.lock` sidecar is intentionally retained after unlocking. A stale-looking sidecar file does not itself mean a lock is held. Store the database on reliable local storage.

The database, `.wal`, `.lock`, and configured `.tmp` directory are excluded when inside the scan root. Nevertheless, putting the database and output reports **outside** the source tree is recommended. Do not hard-link the database into the source tree.

## Development and verification

```bash
./gradlew test installDist distTar
python3 scripts/process-smoke.py
```

Tests cover size/hash filtering, separate phases, multiple scans, nanosecond timestamps, cancellation/restart, checksum reuse, rehashing, changed/missing files, transient failures, parallel workers, filename escaping, symlink/FIFO handling, database exclusions, locking, and CLI exit codes. The process suite additionally uses actual Linux SIGTERM/SIGKILL and reopens the persisted database after interrupted discovery and hashing.

CI uses Ubuntu 24.04 and Java 21 and uploads test reports and the distribution. See `docs/ARCHITECTURE.md`, `REQUIREMENTS.md`, and `AGENTS.md` before changing invariants. The repository's original `LICENSE` is preserved.
