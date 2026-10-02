# Archive analysis

## Runtime and supported families

The coordinator, checksums, database and CLI are Groovy/Java. A bundled Python 3 helper calls the system's **native libarchive** shared library through ctypes. The helper resolves the platform library name at runtime instead of requiring a specific SONAME; `FNORD_LIBARCHIVE` may point to an explicit shared-library path when normal platform discovery is insufficient. Python needs no pip packages. Ubuntu 24.04 packages are `python3 libarchive13t64`; package names can differ on other distributions. Archive analysis requires the native runtime, but the normal filesystem scanner and its build/tests do not. Native-dependent tests are skipped when that optional runtime is absent; CI installs it and executes them. Do not run extraction as root. Keep the native library updated.

Candidate filename families are ZIP, TAR, tgz/tbz/tbz2/txz/tzst, gzip/bzip2/xz/zstd, RAR and 7z, including `.partN.rar`, old `.rar/.r00...`, `.7z.001...`, `.zip.001...`, and `.z01.../.zip` volumes. The native reader validates the actual format. Extension matching is a cheap filter, not proof of validity. Unrecognized extensions are not searched by reading every file's contents.

Format and codec coverage depends on the installed native library. Unsupported methods, encrypted contents and corrupt headers are reported as errors, not silently counted as valid. Password input, archive repair and a native unrar fallback are not implemented. Missing or damaged solid-stream data may make later members unrecoverable; recovery is best effort, not a promise to reconstruct arbitrary corruption.

Compressed TAR is interpreted as TAR plus compression filters in one result. A bare compressed stream has the stable logical member name `data` when the format does not provide a member catalog; renaming an outer gzip file must not alter shared result identity. Native ZIP timestamps without a timezone are interpreted with the helper timezone fixed to UTC. Metadata precision is retained when the format/library provides it; unknown timestamps remain null rather than being invented.

## Commands

After `scan --discover-only` or a completed normal scan:

```bash
DB="$HOME/scans.duckdb"
./bin/fnord-dedup2 --db "$DB" archives --name "scan-01" \
  --archive-temp /fast-disk/fnord-temp
```

The database lock is exclusive across application processes, including report commands. Close/pause the active invocation before opening reports in another process.

```bash
./bin/fnord-dedup2 --db "$DB" archive-status --name "scan-01"
./bin/fnord-dedup2 --db "$DB" archive-list --name "scan-01"
./bin/fnord-dedup2 --db "$DB" archive-errors --name "scan-01"
./bin/fnord-dedup2 --db "$DB" archive-entries --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" archive-volumes --result RESULT_UUID
```

Status/work summaries are JSON; list/entries/volumes/errors are streaming JSON Lines. Redirect stdout for scripting. Progress goes to stderr. `--quiet` suppresses progress. An EXTRACTING interval can include a long member before the next committed-member event.

Exit conventions are unchanged: 0 success, 1 configuration/execution failure, 2 CLI usage error, 3 finished with recorded errors, 130 cooperative cancellation. OS shutdown can instead return its signal status. Partial archive contents remain queryable and must be interpreted with their status/errors.

## Status and integrity

The archive run has IDENTIFYING, ANALYZING, PAUSED, COMPLETE or COMPLETE_WITH_ERRORS. These are durable work phases, not process-liveness claims.

Each root job has PENDING, RUNNING, COMPLETE or PARTIAL, with independent duplicate and retryable flags. COMPLETE means this attempt finished without reported native, resource, safety, child or I/O errors. It is not a forensic guarantee that every possible corruption has been detected.

A content result has a UUID and state RUNNING, COMPLETE or PARTIAL. Only finalized results may be enumerated through archive-entries. Each member has an ordinal, so two archived files with the same pathname remain distinct.

| Member integrity | Meaning |
| --- | --- |
| READ_OK | Regular file read to completion without a reported member error; expected size matched where known; SHA-256 calculated. Use sha256 with actual_size for comparisons. |
| DAMAGED / UNREADABLE | Native integrity or I/O failure. Recovered bytes may have recovered_sha256, but never a normal sha256. Do not include them in confirmed duplicate queries. |
| METADATA / SKIPPED / ENCRYPTED | Non-regular, unsafe, or encrypted member; no normal content hash. |

Errors distinguish content, operational, safety, limit, capability and nested-child diagnostics. Native messages are bounded. An error in one member does not erase successfully indexed siblings. If a damaged header prevents further enumeration, no rows are fabricated for unknown members.

## Multi-volume identity and deduplication

Volumes are grouped in their immediate filesystem directory or parent archive's logical directory. They are numerically ordered, never lexicographically. Ambiguous duplicate slots are rejected rather than guessed. Gaps and missing leads are recorded; available parts are attempted where safe. ZIP end records, RAR final-volume markers and 7z extents supplement native error reporting when checking completeness.

A single archive has its full SHA-256. A multi-volume fingerprint hashes a versioned serialization of the volume family, count, slot numbers, sizes and individual SHA-256 values. Different compressed bytes or volume layouts are not equivalent merely because their extracted files match.

The cache additionally requires compatible provider/version/policy and sufficient remaining nesting depth. Incomplete, unknown-completeness, encrypted, operationally failed and resource-limited results are not reusable. Stable partial results with recoverable member-level corruption may be reused, preserving their partial status and errors.

Archive-list emits root jobs and canonical nested edges. Its source field is a logical set label, not necessarily a physical filename. A root alias retains its own archive_inputs provenance and directly references a canonical result; archive-volumes describes that result's original volumes. Nested edges relate parent_result_id, source member ordinal and child result. Expanding every outer-copy/nested-path combination is a client operation, not duplicated member storage.

Aggregate member/byte counts count **unique reachable content results**, not a multiplication for every alias. They are not physically reclaimable disk-space estimates. There is no archive-member duplicate grouping command yet; the stored size/SHA-256 fields permit future filesystem/member and member/member comparisons.

## Stop, resume, retry

Press Ctrl+C or send SIGTERM. Repeat the same archives command to continue. Ordinary filesystem resume does not activate or resume archive extraction.

The current archive is replayed from its beginning, not resumed mid-decoder. Batches committed to an unfinished generation remain hidden and are deleted on recovery. Completed child results survive interrupted parents and can be reused when parents are extracted again. Completed root results are retained.

Operational failures retry on the next archive invocation. Terminal partial results require `--retry-errors`; `--force` reprocesses all roots and bypasses caches. Old immutable results are retained rather than mutating references used by other scans; explicit historical-result garbage collection is not implemented.

This is not a source refresh. Adding a missing volume, replacing a corrupt archive, or changing source contents requires a **new named filesystem scan**. Top-level volume size and modification time are checked before and after reading. Metadata checks cannot detect every hostile same-size modification with restored timestamps. Use a stable source or snapshot for stronger consistency.

DuckDB transactions/WAL remain enabled. Keep a crashed database and its WAL together; do not copy a live database. A copied database at a new path will not clean the original database's temporary namespace.

## Temporary data and limits

At most one native extractor is active. Parent data stays on disk while nested archives are processed depth-first. A split 7z set needs an additional seekable joined compressed input; originals are untouched.

Archive-temp must be outside the scanned root. Its default is adjacent to the database: `<database>.archives-tmp`. Work directories are private, marked and leased. Native parent-death signaling stops the helper when the controller dies. Startup cleans recognized interrupted work, including after a configured temporary-parent change. Cleanup does not follow links. Namespace ownership/lock control files may remain; extracted payloads are deleted.

Archive paths are never output paths. Regular contents use numeric filenames; symlinks, hardlinks, device nodes and FIFOs are not recreated. Unsafe absolute/traversal/invalid names get metadata/errors and are skipped. This is path hardening, **not a full OS sandbox against a native decoder vulnerability**. Strongly hostile inputs require an isolated unprivileged environment with host-enforced quotas and a read-only source mount.

| Option | Default | Meaning |
| --- | ---: | --- |
| --archive-max-depth | 32 | Root depth is 0; deeper archives receive a limit error. |
| --archive-max-files | 1,000,000 | Headers/members per extraction, including non-regular entries. |
| --archive-max-expanded-bytes | 107,374,182,400 | Per-extraction temporary-byte budget; joined split-7z input also consumes it. |
| --archive-max-temp-bytes | 107,374,182,400 | Combined active ancestor/child temporary data budget. |
| --archive-temp-min-free | 1,073,741,824 | Free-space reserve checked before writes. |
| --archive-native-memory-bytes | 2,147,483,648 | Helper address-space limit, separate from Java/DuckDB. |
| --archive-timeout-seconds | 3600 | Wall-clock limit per extractor invocation. |
| --archive-max-volumes | 10,000 | Maximum physical volumes in one set. |
| --python | /usr/bin/python3 | Absolute Python executable path. |

Byte options use bytes, not suffix strings. Defaults are deliberately finite. Increase them explicitly for large archives. Free-space checks cannot reserve space against another process; use filesystem quotas for a hard host-wide limit. Raising depth/temp limits increases possible disk requirements.

JDBC writes use bounded Appender batches, explicitly activated transactions and schema-directed numeric widths. Metadata is streamed rather than accumulating every member in JVM memory. No performance multiplier has been measured on user storage.

## Scripting API

```groovy
import fnord.dedup.Dedup
import fnord.dedup.archive.ArchiveOptions
import groovy.json.JsonOutput
import java.nio.file.Path

Dedup.open(Path.of('/data/scans.duckdb')).withCloseable { d ->
    d.analyzeArchives('scan-01', new ArchiveOptions(
        tempDirectory: Path.of('/fast/temp')
    ))

    // Keep only one ID here. Do not issue another query from this callback.
    String firstResult = null
    d.eachArchive('scan-01') { Map archive ->
        println JsonOutput.toJson(archive)
        if (firstResult == null && archive.result_id != null) {
            firstResult = archive.result_id as String
        }
    }
    // The previous streaming query is now closed.
    if (firstResult != null) {
        d.eachArchiveEntry(firstResult) { Map member ->
            println JsonOutput.toJson(member)
        }
    }
}
```

Never issue reentrant queries from a streaming callback on the same engine. All operations are synchronous; one thread owns the engine. A StopToken may be cancelled from another thread. The executable examples/archives.groovy shows signal handling, progress and a streaming archive list without loading all results into memory.

## References

- Native API: https://www.libarchive.org/
- RAR 5 headers and volume flags: https://www.rarlab.com/technote.htm
- DuckDB Appender: https://duckdb.org/docs/current/clients/java/data_import
- Pinned RAR5 test data: https://github.com/libarchive/libarchive/tree/d294297f9ecade3b2446b677bd087ad84fb7965a/libarchive/test
