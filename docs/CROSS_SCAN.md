# Cross-scan duplicate verification

## Command

Select at least two distinct existing names explicitly:

```bash
./bin/fnord-dedup2 --db "$HOME/scans.duckdb" cross-duplicates \
  --scan "Laptop 2026" --scan "NAS 2026" --scan "USB Backup" \
  --workers 2 --format jsonl > cross-duplicates.jsonl 2> cross-verification.log
```

`--scan` is repeatable, takes one exact name and never splits commas. Unknown names, repeated names (even alongside other names), fewer than two names, unfinished discovery/checkpoints and incompatible algorithms are refused before any new hashes are written. Names retain the database's case-sensitive semantics. A name starting with a dash can be supplied with `--scan=-name`.

Scans in `READY`, interrupted `HASHING`, `COMPLETE`, or `COMPLETE_WITH_ERRORS` can participate after discovery finishes. Unselected scans never affect candidate selection, new hashing or duplicate groups.

The default `--format table` groups human-readable occurrences; `text` is an alias. `--format jsonl` emits **only duplicate occurrences on stdout**, one per line. Paths/names are JSON-escaped in both formats so embedded newlines cannot be mistaken for a second occurrence. The final JSON summary, progress and individual structured errors go to **stderr**. `--quiet` suppresses progress only, not errors or the final summary (`event: cross_scan_summary`). Finding duplicates is not an error.

## Selection and hashing contract

A size becomes eligible only when regular files of that size exist in **at least two distinct selected scans**. Repetition within one scan alone is insufficient. Every missing hash in an eligible size group is attempted, not one representative per scan. Files in other sizes are never opened by this operation.

Existing completed SHA-256 values in `hashes` are reused **without even statting their source files**. This permits comparison of imported or offline inventories when relevant hashes are already known. Missing candidate hashes require their current source file. The shared `FileHashTask` verifies regular-file type, exact byte size and modification seconds/nanoseconds before and after streaming through the existing `FileHasher`; the returned byte count and digest representation are checked as well.

Only valid complete digests are added to the normal `(scan_id, entry_id, sha256)` table. An existing digest is never replaced; conflicting keys/invalid selected hashes are refused, not silently multiplied. `scans` (including phases/timestamps), `entries`, `directories`, `scan_errors` and all archive/image tables are unchanged. No new persistent tables, checksum-status flags, saved comparisons or migrations are introduced.

Ordinary `hash` and `duplicates --name ...` semantics stay unchanged. They can reuse the additional normal hashes. An explicit ordinary `--rehash` still clears the scan's saved hashes according to its existing behavior, including hashes obtained by earlier cross-scan comparisons.

## Results and coverage

Final groups require **same size AND same SHA-256 AND at least two distinct selected scan IDs**. Every matching occurrence is reported, including multiple matching files within the same scan once another scan participates. A same-scan-only digest group is not reported.

Each occurrence carries `scan_id`, `scan_name`, `scan_root`, `entry_id`, `relative_path`, `filename`, `path`, `size`, modification time, `sha256`, `copies`, `scan_count`, deterministic `group_id` (`size:sha256`), and `partial`. Ordering is size descending, digest, requested scan order, relative path and entry ID. Counts are **observations**, not physical copies or reclaimable space. Two scans of the same absolute path remain separate observations.

The summary includes:

- `candidate_sizes`, `candidate_files`, `existing_candidate_hashes`, `hashes_needed` at preparation;
- `hashes_attempted`, `hashes_completed_this_run` (committed only), `hash_failures`, `unresolved_candidates`;
- `duplicate_groups`, `duplicate_observations`, `duplicate_observations_reported`, `report_complete`;
- `selected_scans`, `prior_scan_errors`, `scan_warnings`, bounded `error_samples`, `partial`, `cancelled`, and `phase`.

A missing, changed, newly nonregular or unreadable candidate is **unresolved**, never a proven nonduplicate. Other candidates continue and proven duplicate groups remain reportable. Error codes include `FILE_MISSING`, `SOURCE_CHANGED`, `NOT_REGULAR_ANYMORE`, `PERMISSION_DENIED` and `READ_ERROR`. Provider programming/contract errors and database failures abort the invocation rather than masquerading as ordinary file failures.

`partial` is conservative: it is true when candidates remain unresolved, cancellation occurs, or selected scans have existing errors / `COMPLETE_WITH_ERRORS`. Pre-existing scan errors are warnings; they are neither removed nor copied into a new persistent error table. Per-file comparison errors stream to the current CLI/API caller. At most 20 error samples are retained by default; the total counter is not capped.

The operation phase is its own returned status: `COMPLETE`, `COMPLETE_WITH_ERRORS`, or `PAUSED`. It does not rewrite any `scans.phase`. Exit codes are 0 for complete clean coverage, 1 for validation/execution failures, 2 for CLI syntax errors, 3 for partial/error coverage, and 130 for cooperative cancellation. OS signals can yield their normal signal-derived exit status.

## Stop and rerun

Ctrl+C/SIGTERM requests cancellation. Stop submitting work, retain collected complete hashes in bounded commits, and discard unfinished/uncollected work. An abrupt SIGKILL preserves prior commits while DuckDB recovers uncommitted work. Keep the database and its WAL together.

Repeat the **same `cross-duplicates --scan ...` command**. Candidates are rebuilt from the selected inventories and only still-missing hashes are attempted. There is no durable cross-scan task queue. No `resume` or scan-phase manipulation is needed. The normal exclusive sidecar lock applies, including to reports and merge operations.

Cancellation before grouping leaves duplicate totals null and `report_complete: false`, not a false zero-duplicate conclusion. During output, cancellation may leave an incomplete occurrence stream; inspect the final summary and exit status. Already emitted matches remain valid. Query cancellation is cooperative between storage operations; a long DuckDB query or uninterruptible filesystem I/O may delay a graceful response. Previously committed hashes remain available even if output or a consumer callback later fails.

## Groovy API

```groovy
import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.cross.CrossScanOptions
import groovy.json.JsonOutput
import java.nio.file.Path

Dedup.open(Path.of('/data/scans.duckdb'), new ScanOptions(workers: 2)).withCloseable { d ->
    def options = new CrossScanOptions(
        onError: { Map error -> System.err.println(JsonOutput.toJson(error)) },
        maxErrorSamples: 20
    )
    Map summary = d.crossDuplicates(['Laptop 2026', 'NAS 2026'], options, new StopToken()) { Map row ->
        println JsonOutput.toJson(row)
    }
    System.err.println(JsonOutput.toJson(summary))
}
```

The convenience form `d.crossDuplicates(['A','B']) { row -> ... }` returns the same summary. Hash tuning reuses `ScanOptions`: workers, buffer bytes, batch size, checkpoint interval and DuckDB limits. `CrossScanOptions` contains only the error callback and bounded sample limit (0..1000). Callbacks execute synchronously on the coordinator, not worker threads. They must not reenter this engine or issue overlapping database operations. Only `StopToken` is intended for cross-thread cancellation.

`examples/cross-duplicates.groovy` accepts `DATABASE SCAN_NAME SCAN_NAME [SCAN_NAME ...]` through the bundled launcher and includes signal handling. No separately installed Groovy or native archive/image runtime is needed.

## Implementation and performance

`CrossScanStore` owns parameterized selection, one distinct-scan size aggregation, session-only materialized candidate tables, ordinal/keyset paging, batched persistence and final grouping. `CrossScanEngine` owns a bounded pool with at most twice the worker count submitted at once. The existing `FileHasher` handles streaming and reusable worker buffers through `FileHashTask`, also used by ordinary hashing.

The coordinator alone owns JDBC. Bounded completed rows bulk-append into a temporary primary-keyed batch under an explicitly activated transaction, then a set-based insert adds only absent hashes. Session tables are dropped on normal closure and never transferred by database merge. Large candidate/result sets remain in DuckDB; they are not accumulated as a JVM list. Query execution may spill according to existing DuckDB memory/temp settings. No user-storage throughput benchmark is claimed.

## Boundaries

This compares normal **filesystem entries only**. ZIPs and VM disk files may match as whole regular files; their contents are not opened or extracted. Archive members and image guest entries remain outside this command. It does not delete, rename, hardlink, or modify sources.

Saved hashes represent historical observations, not a fresh synchronized view. Newly needed hashes use the existing size/mtime observational check, which cannot detect every same-size content change with restored timestamps or hostile concurrent source-tree replacement. Use stable snapshots for stronger guarantees. SHA-256 matches are treated as duplicates; byte-for-byte confirmation, rehash switches, automatic all-scan selection, root remapping and physical space-savings estimates are not implemented here.

## Verification

Run `./gradlew test installDist distTar` and existing filesystem/archive/merge suites. `python3 scripts/cross-scan-smoke.py` exercises the installed CLI/launcher, actual SIGKILL and SIGTERM after committed cross-scan hashes, repeat invocation, unchanged scan state, unselected scans and a 100,000-observation offline report. `CrossScanTest` covers candidate gating, different content, zero-byte files, metadata mutation, errors/retry, unusual names, bounded batches, invalid selections/digests and database-merged scans.
