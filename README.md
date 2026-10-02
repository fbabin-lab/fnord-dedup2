# fnord-dedup2

Linux duplicate-file analysis in **Groovy**, with a **CLI**, a **synchronous scripting API**, and an embedded **DuckDB** database containing multiple named scans. No server or separate Groovy installation is required.

The application never deletes, moves, hard-links, or rewrites source files. Archive extraction writes only disposable temporary data.

## Build

On Ubuntu 24.04, install Java and build prerequisites. Archive analysis additionally needs Python 3 and native libarchive:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk git curl unzip util-linux python3 libarchive13t64

git clone https://github.com/fbabin-lab/fnord-dedup2.git
cd fnord-dedup2
./gradlew test installDist
./bin/fnord-dedup2 --help
```

`gradlew` is the existing Linux-only bootstrap: it downloads a pinned Gradle distribution and verifies its SHA-256. The first build needs Internet access. The installed distribution is `build/install/fnord-dedup2/`; `./bin/fnord-dedup2` launches it without starting Gradle. Rebuild after source changes. Java 21 is required at runtime.

## Filesystem phases

```bash
DB="$HOME/scans.duckdb"

./bin/fnord-dedup2 --db "$DB" scan --name "archive-01" --root /data/source
./bin/fnord-dedup2 --db "$DB" duplicates --name "archive-01" --format jsonl
```

Discovery records paths, filenames, types, sizes, and modification dates without reading file contents. Hashing then reads only regular files that share a size within that scan; matching size and full-file SHA-256 define a duplicate group. Inode/permissions/ownership/block/link-count metadata are not collected.

Use `scan --discover-only` and then `hash --name ...` to separate those phases. Stop with Ctrl+C and continue with `resume --name ...`. Completed hashes are reused; interrupted work is redone. Resume continues the saved inventory; it does not refresh changed directories.

See [the detailed filesystem guide](docs/FILESYSTEM.md) for commands, tuning, crash recovery and correctness boundaries.

## Optional archive phase

Archive analysis starts **only when explicitly requested**, after filesystem discovery. It does not require the ordinary hashing phase to have run.

```bash
./bin/fnord-dedup2 --db "$DB" archives --name "archive-01" \
  --archive-temp "$HOME/fnord-archive-temp"

./bin/fnord-dedup2 --db "$DB" archive-status --name "archive-01"
./bin/fnord-dedup2 --db "$DB" archive-list --name "archive-01"
./bin/fnord-dedup2 --db "$DB" archive-errors --name "archive-01"

# Copy a result_id returned by archive-list:
./bin/fnord-dedup2 --db "$DB" archive-entries --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" archive-volumes --result RESULT_UUID
```

The native libarchive engine handles extraction through a bounded Python bridge. The application does not parse human-oriented `tar`, `unzip`, or `unrar` output. There is only one active extractor; nested archives are processed depth-first in separate temporary directories.

Archive members retain their logical paths, filenames, types, sizes, timestamps and SHA-256. Exact duplicate archive sets share an immutable canonical result rather than being re-extracted. This applies to nested archives and other named scans in the same database. Corrupt members are distinguished from readable members; damaged recovered bytes never receive a normal confirmed-content hash.

Run the same `archives --name ...` command after interruption. The current archive may be extracted again; completed results remain available. **Ordinary `resume` still handles only filesystem phases.** Use `archives --retry-errors` for terminal partial results, or `archives --force` to bypass caches. Create a new named filesystem scan after adding missing volumes or changing source files.

Temporary storage must be outside the scanned source tree. Defaults include depth 32, one million members per archive, 100 GiB per extraction and temporary stack, a 1 GiB free-space reserve, and a one-hour extractor timeout. These are adjustable; see [the archive guide](docs/ARCHIVES.md).

## Scripting

The distribution includes its Groovy runner:

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy \
  examples/archives.groovy "$DB" "archive-01" "$HOME/fnord-archive-temp"
```

The same `Dedup` API exposes `analyzeArchives`, `archiveStatus`, `eachArchive`, `eachArchiveEntry`, `eachArchiveVolume`, and `eachArchiveError`. All operations are synchronous and use the same DuckDB lock as the CLI. See the example and [archive API documentation](docs/ARCHIVES.md#scripting-api).

## Verification and development

```bash
./gradlew test installDist distTar
python3 scripts/process-smoke.py
python3 scripts/rollback-smoke.py
python3 scripts/archive-smoke.py
```

For the complete format matrix, install `zip` and `p7zip-full`, then run:

```bash
python3 scripts/archive-smoke.py --require-7z --upstream-rar5
```

That optional RAR5 test downloads data-only fixtures from an immutable libarchive commit; ordinary operation never downloads anything. CI runs this matrix on Ubuntu 24.04 / Java 21, including real SIGTERM/SIGKILL recovery, and publishes reports and distributions. A portable `testHarness` Gradle task can package sources and public test dependencies for offline reproduction.

Read [AGENTS.md](AGENTS.md), [REQUIREMENTS.md](REQUIREMENTS.md), [filesystem architecture](docs/ARCHITECTURE.md), and [archive implementation contract](docs/ARCHIVE_SPEC.md) before changing invariants. The original LICENSE is preserved. No real-storage throughput benchmark is claimed.
