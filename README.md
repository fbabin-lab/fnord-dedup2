# fnord-dedup2

Duplicate-file analysis in **Groovy**, with a **CLI**, a **synchronous scripting API**, and an embedded **DuckDB** database containing multiple named scans. No server or separate Groovy installation is required.

The application never deletes, moves, hard-links, or rewrites source files. Container analysis writes only disposable temporary data.

## Platforms

Core filesystem discovery, SHA-256 hashing, duplicate reports, cross-scan verification, database merge, CLI and Groovy scripting are supported on **Linux and Windows** with Java 21. Paths persisted in DuckDB use forward slashes on both platforms: C:/Data, //server/share and /data. Native filesystem I/O still uses java.nio.file.Path.

Archive analysis and disk/VM-image analysis remain **Linux-only**. Windows core support does not require Python, libarchive, QEMU, libguestfs, WSL, Cygwin or Git Bash.

Windows PowerShell:

    .\gradlew.bat windowsCoreTest installDist
    .\bin\fnord-dedup2.bat --help
    .\bin\fnord-dedup2.bat --db "$HOME\scans.duckdb" scan --name "Photos" --root "D:\Photos"

See [the Windows guide](docs/WINDOWS.md) for drive/UNC paths, foreign-platform databases, locked files and limitations.

## Build

On Ubuntu 24.04:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk git curl unzip util-linux python3 libarchive13t64

git clone https://github.com/fbabin-lab/fnord-dedup2.git
cd fnord-dedup2
./gradlew test installDist
./bin/fnord-dedup2 --help
```

`gradlew` is a Linux-only bootstrap that downloads a pinned Gradle distribution and verifies its SHA-256. The first build needs Internet access. The installation is `build/install/fnord-dedup2/`; `./bin/fnord-dedup2` launches it without starting Gradle. Rebuild after source changes. Java 21 is required at runtime.

## Filesystem phases

```bash
DB="$HOME/scans.duckdb"
./bin/fnord-dedup2 --db "$DB" scan --name "archive-01" --root /data/source
./bin/fnord-dedup2 --db "$DB" duplicates --name "archive-01" --format jsonl
```

Discovery records paths, filenames, types, sizes and modification dates without reading file contents. Hashing reads only regular files that share a size within that scan; matching size and full-file SHA-256 define a duplicate group. Inode/permissions/ownership/block/link-count metadata are not collected.

Use `scan --discover-only` then `hash --name ...` to separate the phases. Stop with Ctrl+C and continue with `resume --name ...`. Completed hashes are reused; interrupted work is redone. Resume continues the saved inventory; it does not refresh changed directories.

See [the filesystem guide](docs/FILESYSTEM.md) for commands, tuning, recovery and correctness boundaries.

## Cross-scan duplicates

Explicitly select at least two distinct scans whose discovery has finished:

```bash
./bin/fnord-dedup2 --db "$DB" cross-duplicates \
  --scan "Laptop 2026" --scan "NAS 2026" --scan "USB Backup" \
  --workers 2 --format jsonl > cross-duplicates.jsonl 2> cross-verification.log
```

Only regular files whose size appears in two or more selected scans become candidates. Saved hashes are reused without reopening their files; only missing candidate hashes are calculated. Matching size and SHA-256 must span at least two selected scans to be reported. Unselected scans and same-scan-only groups are excluded.

New hashes persist in the normal hashes table. Scan phases, scan timestamps, inventories and scan errors are unchanged. Repeat the same command after interruption; completed hashes are reused. Unknown/repeated names and unfinished discovery are refused before hashing. Missing/changed files remain unresolved, not proven nonduplicates.

Results stream on stdout; the final JSON summary and errors go to stderr even with `--quiet`. The Groovy API is `d.crossDuplicates(['A','B']) { row -> ... }`; an example is in `examples/cross-duplicates.groovy`. See [the cross-scan guide](docs/CROSS_SCAN.md) for coverage, exit codes, cancellation and offline/historical semantics. This compares filesystem entries, not archive members or image guest files, and does not estimate reclaimable space.

## Optional archive phase

Archive analysis starts **only when explicitly requested**, after filesystem discovery. Ordinary hashing need not have run.

```bash
./bin/fnord-dedup2 --db "$DB" archives --name "archive-01" --archive-temp "$HOME/fnord-archive-temp"
./bin/fnord-dedup2 --db "$DB" archive-status --name "archive-01"
./bin/fnord-dedup2 --db "$DB" archive-list --name "archive-01"
./bin/fnord-dedup2 --db "$DB" archive-errors --name "archive-01"

# Copy a result_id from archive-list:
./bin/fnord-dedup2 --db "$DB" archive-entries --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" archive-volumes --result RESULT_UUID
```

Native libarchive extracts through a bounded Python bridge. The application does not parse human-readable tar/unzip/unrar output. Only one extractor is active; nested archives are processed depth-first in separate temporary directories.

Members retain logical paths, filenames, types, sizes, available timestamps and SHA-256. Exact duplicate archive sets share immutable canonical results instead of being extracted again, including nested archives and other named scans in the same database. Damaged recovered bytes never receive normal confirmed-content hashes.

Repeat `archives --name ...` after interruption. The current archive may be extracted again; completed results survive. Ordinary `resume` still handles only filesystem phases. Use `archives --retry-errors` for terminal partial results or `--force` to bypass caches. New volumes or changed source files require a new named scan.

Temporary storage must be outside the scanned tree. Finite adjustable defaults include depth 32, one million members per archive, 100 GiB per extraction/temporary stack, 1 GiB free reserve and one-hour extractor timeout. `--archive-min-size-bytes N` optionally skips root and nested logical archive sets smaller than N bytes without treating them as errors; multipart volume sizes are summed. See [the archive guide](docs/ARCHIVES.md).

## Optional disk-image phase

Image analysis is separately requested after discovery:

```bash
sudo apt-get install -y qemu-utils python3-guestfs libguestfs-tools
libguestfs-test-tool

./bin/fnord-dedup2 --db "$DB" images --name "archive-01" --image-temp "$HOME/fnord-image-temp"
./bin/fnord-dedup2 --db "$DB" image-status --name "archive-01"
./bin/fnord-dedup2 --db "$DB" image-list --name "archive-01"
./bin/fnord-dedup2 --db "$DB" image-errors --name "archive-01"

# Copy a result_id from image-list:
./bin/fnord-dedup2 --db "$DB" image-filesystems --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-partitions --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-components --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-entries --result RESULT_UUID
```

Candidate families: VMDK, QCOW1/QCOW2, VDI, VHD/VHDX, QED, IMG/RAW/DD, ISO and DMG. QEMU decodes approved storage read-only; libguestfs discovers partitions/filesystems and streams guest files into SHA-256. Its trusted inspection appliance is started; the source guest OS is never booted. No root execution, host filesystem mounts, repair or source writes are permitted.

Run as a regular user on a Linux host with Landlock enabled and a working libguestfs appliance. Dependencies must be present in the saved inventory, safe and explicitly typed. Exact complete component fingerprints can reuse canonical results. Unsupported format variants, missing dependencies, encrypted/unmountable filesystems and failed reads produce errors, not invented file contents.

Repeat `images --name ...` after interruption. Use `--image-container-hash candidate|always|never` to control physical image-hashing cost; every readable guest regular file is still hashed. Temporary storage defaults to `<database>.images-tmp` and must be outside the source root. See [the image guide](docs/IMAGES.md) for installation diagnostics, limits, read-only guarantees, format boundaries and retry semantics.

Nested archive/image dispatch, differencing VHD/VHDX, password management, OS inventory, DMG conversion fallback and combined cross-domain duplicate reports are not implemented in this phase. Container support is separate from inner-filesystem support.

## Scripting

The distribution includes a Groovy runner:

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy examples/archives.groovy "$DB" "archive-01" "$HOME/fnord-archive-temp"
build/install/fnord-dedup2/bin/fnord-dedup2-groovy examples/images.groovy "$DB" "archive-01" "$HOME/fnord-image-temp"
build/install/fnord-dedup2/bin/fnord-dedup2-groovy examples/cross-duplicates.groovy "$DB" "Laptop 2026" "NAS 2026"
```

`Dedup` exposes filesystem, cross-scan, archive and image analysis APIs. Operations are synchronous and use the same exclusive database lock as the CLI. Streaming callbacks must not issue reentrant queries. See the examples and domain guides.

## Database merge

Import all scans from one existing database into another, with the source read-only. Any overlapping scan name refuses the entire import. Every imported scan ID and archive/image result UUID is remapped; canonical sharing, nested relationships and metadata are retained. Both databases are audited and all inserts commit atomically. No referenced source files or native archive/image tools are needed.

```bash
build/install/fnord-dedup2/bin/fnord-dedup2-groovy scripts/merge-database.groovy \
  --source /data/source.duckdb --destination /data/master.duckdb --dry-run

# Remove --dry-run to perform the import after a successful preflight.
```

The script is included under `scripts/` in the installed distribution. Close other application sessions using either database first. See [the merge guide](docs/MERGE.md) for validation, cancellation, output, Groovy API, WAL handling and unsupported states. The source's temporary-root registrations and feature instance IDs are never copied.

## Verification and development

```bash
./gradlew test installDist distTar
python3 scripts/process-smoke.py
python3 scripts/rollback-smoke.py
python3 scripts/archive-smoke.py
python3 scripts/database-merge-smoke.py
python3 scripts/cross-scan-smoke.py
```

For the full archive matrix install zip and p7zip-full and run `python3 scripts/archive-smoke.py --require-7z --upstream-rar5`. That optional RAR5 test downloads data-only fixtures from an immutable libarchive commit; ordinary operation never downloads anything.

Image native verification additionally uses `/usr/bin/python3 scripts/image-preflight.py`, `/usr/bin/python3 scripts/image-smoke.py` and `/usr/bin/python3 scripts/image-extra-smoke.py`; fixture/runtime requirements are in docs/IMAGES.md. Tests compare source-image checksums and exercise actual process termination. Native support must be established by native tests, not mock-provider tests alone.

Read [AGENTS.md](AGENTS.md), [REQUIREMENTS.md](REQUIREMENTS.md), [filesystem architecture](docs/ARCHITECTURE.md), [cross-scan contract](docs/CROSS_SCAN.md), [archive contract](docs/ARCHIVE_SPEC.md), and [image guide](docs/IMAGES.md) before changing invariants. The original LICENSE is preserved. No real-storage throughput benchmark is claimed.
