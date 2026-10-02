# Version 0.1 requirements

The Linux application must be implemented in Groovy and expose equivalent CLI and reusable scripting entry points. It must recursively inventory one named root per scan and store many independent, named scans in one embedded DuckDB database.

## Filesystem phases

Pass one stores directory/file paths, filename, type, byte size and modification time without reading file contents. Filesystem inode, permissions, ownership, block allocation and link counts are intentionally excluded. Pass two hashes only regular files whose size appears more than once in that scan, then reports groups with matching size and full SHA-256.

Discovery must be safely resumable using durable directory work and atomic chunk checkpoints. Hashing persists completed checksums only; interrupted work may be recomputed. Metadata errors and changing files must be visible, not silently counted as verified duplicates. The source tree must never be modified.

Batch/bulk ingestion, bounded application memory, bounded hash concurrency, reusable buffers, and an analytical DuckDB query model are required. Tuning options must not weaken durability. Starting one native process per file is not a requirement; checksum implementation is extensible.

Minimum filesystem commands: scan, resume, hash, list, status, duplicates, errors. Required machine-readable surfaces: JSON status/list/work summaries and streaming JSON Lines duplicate/error reports. Incomplete filesystem results require an explicit opt-in. Root, name, database, concurrency and batch settings must be validated.

## Optional archive phase

After discovery, an explicit archives command/API must analyze archive candidates, including unique-size files not read by ordinary hashing. Use a replaceable native extraction provider. Extract one archive at a time into private temporary storage, index every encountered member's logical path, filename, type, declared/actual size and available modification timestamp, and stream SHA-256 for recovered regular files. Original files are read-only; extracted payloads are disposable.

Support common ZIP, TAR/compressed TAR, RAR, 7z and bare compressed-stream families when supported by the native library. Group numeric multipart volumes within their logical directory/container, preserve volume provenance and hashes, detect missing/ambiguous sets, and attempt safe recovery of available content. Capability failures and unavailable credentials must be explicit and noninteractive.

Exact identical archive bytes/ordered volume sets must share canonical contents without duplicate member storage or repeated extraction when a compatible reusable result exists. This applies to nested archives and other named scans in the same database. Operationally failed, unknown/incomplete-volume, encrypted and resource-capped results are not reusable.

Nested archives use the same depth-first processing pipeline, including nested multipart grouping and exact checksum reuse. Parent temporary contents remain available until child work finishes. Archive member errors do not invalidate good siblings. Distinguish a complete SHA-256 of damaged recovered bytes from a normal readable member checksum; damaged bytes never participate in confirmed duplicate reporting.

Persist attempt/generation states with bounded transactional batches. Unfinished generations must remain invisible through authoritative reports, be discarded on restart, and be replayed from the start of that archive. Completed child results must survive parent interruption. Temporary cleanup must validate ownership, honor extraction leases, avoid links, and recover safely from SIGTERM/SIGKILL and interrupted initialization/cleanup.

Enforce finite configurable depth, member-count, native-memory, wall-clock, temporary-byte and free-space limits. Archive names are untrusted metadata, never output paths. Never recreate or follow links or special files. No shell invocation or human tabular-output parsing. No claim of full OS sandboxing.

Archive CLI/API must support explicit analysis/resume, status, streaming archive/member/volume/error reports, terminal-error retry and forced reanalysis. Ordinary filesystem resume remains unchanged. Source changes or new volumes require a new named filesystem inventory. See docs/ARCHIVE_SPEC.md for the executable implementation contract and deliberate design refinements.

## Verification and non-goals

Verification must include actual database persistence/reopening; interruption in discovery, hashing and archive extraction; multiple scan isolation; size/hash discrimination; changed files; special names; symlink/non-regular-file behavior; corruption recovery; multipart/nested/canonical reuse; staged-generation invisibility; limits; cleanup; and installed-CLI process recovery. Tests must not delete or modify data outside their temporary fixtures. Claims of test success must point to an actual successful run.

Non-goals: destructive deduplication, hardlink-aware physical-space calculations, a web interface, remote/multiprocess DuckDB writers, atomic filesystem snapshots, continuous watching, metadata refresh/merge, and cross-scan ordinary filesystem duplicate grouping. Archive password management, repair, recompression, persistent extracted payloads, parallel extractors, semantic equivalence of differently compressed archives, member duplicate-report commands, and historical-result garbage collection are also outside this implementation.
