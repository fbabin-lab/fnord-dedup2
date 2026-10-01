# Version 0.1 requirements

The Linux application must be implemented in Groovy and expose equivalent CLI and reusable scripting entry points. It must recursively inventory one named root per scan and store many independent, named scans in one embedded DuckDB database.

Pass one stores directory/file paths, filename, type, byte size and modification time without reading file contents. Filesystem inode, permissions, ownership, block allocation and link counts are intentionally excluded. Pass two hashes only regular files whose size appears more than once in that scan, then reports groups with matching size and full SHA-256.

Discovery must be safely resumable using durable directory work and atomic chunk checkpoints. Hashing persists completed checksums only; interrupted work may be recomputed. Metadata errors and changing files must be visible, not silently counted as verified duplicates. The source tree must never be modified.

Batch/bulk ingestion, bounded application memory, bounded hash concurrency, reusable buffers, and an analytical DuckDB query model are required. Tuning options must not weaken durability. Starting one native process per file is not a requirement; checksum implementation is extensible.

Minimum commands: scan, resume, hash, list, status, duplicates, errors. Required machine-readable surfaces: JSON status/list/work summaries and streaming JSON Lines duplicate/error reports. Incomplete results require an explicit opt-in. Root, name, database, concurrency and batch settings must be validated.

Verification must include actual database persistence/reopening; interruption in discovery and hashing; multiple scan isolation; size/hash discrimination; changed files; special names; symlink/non-regular-file behavior; and installed-CLI process recovery. Tests must not delete or modify data outside their temporary fixtures. Claims of test success must point to an actual successful run.

Non-goals: destructive deduplication, hardlink-aware physical-space calculations, a web interface, remote/multiprocess DuckDB writers, atomic filesystem snapshots, continuous watching, metadata refresh/merge, and cross-scan duplicate grouping.
