# Archive phase implementation contract — v1

This document refines the requested archive-analysis specification into the implemented architecture. Read it with `ARCHIVES.md`, `REQUIREMENTS.md` and the executable schemas/tests.

## Required behavior

Archive analysis is an optional synchronous phase of an existing named filesystem inventory. Discovery must finish first; ordinary same-size hashing need not run. Archive volume fingerprints and all recovered regular-member hashes are independent of normal filesystem hash candidates. Source files are always read-only.

Only one extractor runs at a time. Nested archives use the same processor, depth-first, while parent payloads remain in separate private temporary directories. Multipart grouping is scoped to one logical directory in one container. Missing/ambiguous volumes are never silently combined with unrelated files.

Every readable regular member gets metadata and SHA-256. A failed member does not erase valid siblings. Damaged bytes have a separate recovered hash, never a normal content hash. Links and special files have metadata only. Header corruption may prevent discovery of later members; do not claim complete coverage in that case.

Exact archive identity is the physical SHA-256, or the versioned ordered multi-volume fingerprint. Canonical reuse requires compatible provider/policy, finalized reusable contents, and a compatible recursion context. Never reuse unknown/incomplete sets, transient failures, encryption failures or limit-capped results as complete results.

## Deliberate implementation choices

**Native API instead of human listing parsers.** `NativeArchiveProvider` invokes a bundled, bounded Python bridge to native libarchive. It does not spawn a shell or parse locale-dependent tabular `tar/unrar/unzip` output. `ArchiveProvider` is the extension seam. Future native-tool providers must meet the same structured-event, path safety, cancellation and resource contracts.

**Streaming catalog plus payloads.** Headers and recoverable contents are read together. The implementation does not require a second full listing/decompression pass, especially for sequential/solid streams. It stores every encountered header, not a fabricated catalog after a fatal parse failure. Optional native compressed-size/CRC fields are not currently exposed in the schema.

**Canonical result DAG.** Root occurrences (`archive_jobs`, `archive_inputs`) point to immutable result UUIDs. A result owns its `archive_members`, `archive_volumes`, errors and nested edges. Duplicates point directly to a result, never to another alias. Nested edges refer to source ordinals and canonical child results. This avoids duplicating millions of members for copied archives and keeps child results reusable after parent interruption.

**Compact status model.** Root/result completion is COMPLETE or PARTIAL with duplicate/retryable/reusable flags and structured diagnostics. Distinct corruption, missing-volume, encryption, resource and operational reasons live in errors, rather than a large ambiguous lifecycle enumeration. Run phases are separately persisted.

**Finite safeguards.** Defaults are depth 32, one million headers, 100 GiB temporary budgets, 1 GiB free-space reserve and one hour per extractor. A limit never silently claims complete coverage and never produces a reusable cache. Password entry/repair are not part of v1.

## Persistence and restart invariants

`archive_schema_info` is independently versioned. Base filesystem schema v1 is unchanged. The feature is initialized transactionally only through an archive API. Reject unrelated/conflicting archive tables rather than guessing a migration.

Each `archive_results` UUID is an attempt/generation. It starts RUNNING. Members/errors/volumes can be committed in bounded transactions while processing. Only after all results, errors and nested edges have been flushed is that generation finalized. Reports refuse RUNNING generations. Parent publication occurs after child outcomes are known.

On restart, delete rows belonging only to RUNNING generations and reset RUNNING roots. Keep completed child generations even if their parent never finalized. Resume by re-extracting the interrupted archive; never resume a native decoder midway. Archived content rows are authoritative only through a finalized reachable result, not by direct unfiltered scans of the staging tables.

BulkWriter and ArchiveBatch must activate DuckDB JDBC's lazy manual transaction with a SQL statement before constructing any appender. Numeric appends must match SQL column widths, not the incidental Integer/Long type produced by JSON parsing. Keep schema and appender column-type maps/tests synchronized.

No database access occurs on the native process output threads. The coordinator owns JDBC. Callbacks consume rows/events synchronously and must not run reentrant queries on the same connection.

## Source and temporary safety

Validate top-level volume size/mtime against discovery before hashing, and again after native extraction. Recheck nested payload metadata around hashing. This is observational consistency, not an atomic snapshot or a defense against hostile timestamp restoration.

Member output filenames are numeric ordinals, independent of archive-supplied paths. Reject unsafe logical paths, never recreate links/special nodes, and never open their targets as regular files. This also preserves duplicate archived pathnames without overwriting one occurrence with another.

Temporary namespaces are tied to database instance and canonical database path. Recorded namespaces can be cleaned after a temp-location change but must not be cleaned by a copied database at a different path. Initialize attempt controls before atomically exposing a work directory. Remove data before ownership/lease controls during cleanup. Interrupted setup/final unlink may leave only recognized control files; unmarked directories containing data must be refused, not recursively deleted.

The helper uses a parent-death signal and extraction lease. On normal cancellation the provider terminates the helper, waits and cleans up. Recovery refuses work still leased by a live extractor. Cleanup never follows symlinks. Do not claim a full native-code sandbox.

## Extending

Keep native-provider identity and policy hashing versioned when decoder semantics change. An alternative provider must produce bounded JSON-equivalent records, report member integrity separately from readability, support cancellation, and account for temporary bytes including joined inputs. Unsupported capabilities are explicit failures, never silent content omission.

Preserve bounded pages, process queues, logs, byte buffers and transactions. Extend the candidate/grouping layer and regression fixtures together. Do not introduce parallel extractors without redesigning temporary budgets, canonical result coordination and recovery tests.

Further work may add stronger process sandboxing, selective retry, native unrar fallback, member duplicate reports across filesystem/archive domains, root-specific expanded logical-path reports, password-provider support, physical orphan-generation garbage collection, and measured throughput tuning. These are not silently implied by v1.

## Verification

The unit/integration suite includes archive metadata, candidate hashing independent of normal phases, exact/root/nested/cross-scan reuse, path rejection, resource caps, transient failures, uncommitted generation visibility, source changes, temporary ownership and copied-database safety.

`archive-smoke.py` additionally verifies native TAR/ZIP, stored RAR4 multi-volumes, nested multipart reuse, missing final volume handling, encrypted ZIP, duplicate member names, split ZIP and split 7z, and actual SIGTERM/SIGKILL after committed member batches. The optional upstream RAR5 check uses an immutable libarchive data fixture. Keep all original filesystem tests and process recovery suites passing before publication.
