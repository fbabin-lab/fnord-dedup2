# Contributor / AI instructions

Read README.md, REQUIREMENTS.md, docs/ARCHITECTURE.md, src/main/resources/schema.sql and relevant tests before changes. For archive work also read docs/ARCHIVES.md, docs/ARCHIVE_SPEC.md and src/main/resources/archive/schema.sql. Preserve the original LICENSE. Do not modify fnord-dedup or other repositories as part of this standalone project.

Use Groovy for application code, Java 21, and the pinned Gradle/dependencies. Keep hot traversal/appender/hash loops statically compiled where practical. Keep CLI presentation separate from the scripting API, traversal, storage and hash provider. Do not add a framework/server for a local CLI problem. The bundled Python helper is a narrow structured bridge to native libarchive, not a second application or owner of database/orchestration logic.

Critical filesystem invariants:

1. Never delete, move, hardlink or rewrite source files. Never execute filenames or untrusted values through a shell.
2. Discovery finishes before ordinary hashing starts. Ordinary hash candidates are regular files in a repeated-size group within the same scan. Archive analysis is a separate explicit phase: every archive volume and safe regular archive member needs a checksum regardless of repeated sizes.
3. Persist directory inventory, child work, completions and active checkpoint in one transaction. Partial-parent replay may delete only that parent's immediate-child inventory/work. Children cannot run before parent completion.
4. Only the coordinator owns JDBC. Workers return values. Persist only complete ordinary hashes; do not introduce per-file pending/running checksum task rows.
5. Keep bounded pages/queues/buffers. Do not replace appender ingestion with per-file SQL/commits or load the full tree/results into JVM collections. Appenders require an activated native transaction before use; execute SQL after disabling autocommit. Match numeric appends to SQL column types, not incidental JSON Integer/Long types.
6. Retain transactional/WAL durability. More aggressive unsafe modes are out of scope unless explicitly requested with documented consequences.
7. Preserve modification-time precision, validate before/after hashing, and record errors. Do not silently report partial scans as complete. Resume is not refresh.
8. No filesystem inode, permission/mode, UID/GID, blocks or link-count collection. Internal IDs are allowed and must not be confused with filesystem metadata.
9. Handle unusual UTF-8 filenames safely; never parse filesystem names using lines/whitespace. Keep symlinks and special files out of hashing. Keep DB/WAL/lock/spill paths out of inventory.
10. Version schema/provider semantics explicitly. Do not mix digest algorithms or change compatibility implicitly.

Archive invariants:

- Only one native extractor is active. Recurse depth-first through the same processor; keep parent payloads until child work finishes. Temporary data must be outside the scanned source tree.
- Native member paths never control output filesystem paths. Payload names are numeric ordinals. Never recreate/follow symlinks, hardlinks, FIFOs or devices. Preserve duplicate member names as distinct ordinals. Bound native output, memory, time, and temporary bytes.
- RUNNING result generations are staging, never authoritative. Flush bounded batches before publication. Recovery removes unfinished generations but retains completed children for reuse. Never mutate canonical results referenced by other scans.
- Exact archive fingerprints represent ordered bytes/volume layout, not semantic content equivalence. Scope volume grouping to one immediate logical directory. Do not reuse incomplete/unknown-volume, encrypted, operationally failed or resource-limited results. Provider/policy/depth compatibility is part of cache eligibility.
- A member error does not erase good siblings. Damaged recovered bytes may have recovered_sha256 but never a normal sha256. Never mix recovered hashes into confirmed duplicate queries.
- Temporary cleanup must validate ownership and leases, never follow links, preserve controls until payloads are gone, and never clean another database's namespace after copying a database.
- The native helper must be terminated on cancellation/controller death. No password prompts or external shell/filter commands. Path hardening is not a full native-code sandbox; document this honestly.
- Stream callbacks must not issue reentrant queries on the same JDBC engine. Ordinary resume must not opt users into archive extraction.

Before publishing: `./gradlew test installDist distTar`, `python3 scripts/process-smoke.py`, `python3 scripts/rollback-smoke.py`, and `python3 scripts/archive-smoke.py`. Full CI additionally uses `--require-7z --upstream-rar5`. Add regression tests for fixes. Inspect packaged launchers/examples, not only API methods. Keep GitHub Actions permissions minimal. Do not claim benchmarks, platform support, test execution or CI success without actual evidence.
