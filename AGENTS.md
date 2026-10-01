# Contributor / AI instructions

Read README.md, REQUIREMENTS.md, docs/ARCHITECTURE.md, schema.sql and relevant tests before changes. Preserve the original LICENSE. Do not modify fnord-dedup or other repositories as part of this standalone project.

Use Groovy for application code, Java 21, and the pinned Gradle/dependencies. Keep hot traversal/appender/hash loops statically compiled where practical. Keep CLI presentation separate from the scripting API, traversal, storage and hash provider. Do not add a framework/server for a local CLI problem.

Critical invariants:

1. Never delete, move, hardlink or rewrite source files. Never execute filenames or untrusted values through a shell.
2. Discovery finishes before hashing starts. Only regular files in a repeated-size group within the same scan are hash candidates.
3. Persist directory inventory, child work, completions and active checkpoint in one transaction. Partial-parent replay may delete only that parent's immediate-child inventory/work. Children cannot run before parent completion.
4. Only the coordinator owns JDBC. Workers return values. Persist only complete hashes; do not introduce per-file pending/running checksum task rows.
5. Keep bounded pages/queues/buffers. Do not replace appender ingestion with per-file SQL/commits or load the full tree/results into JVM collections.
6. Retain transactional/WAL durability. More aggressive unsafe modes are out of scope unless explicitly requested with documented consequences.
7. Preserve modification-time precision, validate before/after hashing, and record errors. Do not silently report partial scans as complete. Resume is not refresh.
8. No inode, permission/mode, UID/GID, blocks or link-count collection. Internal IDs are allowed and must not be confused with filesystem metadata.
9. Handle unusual UTF-8 filenames safely; never parse filesystem names using lines/whitespace. Keep symlinks and special files out of hashing. Keep DB/WAL/lock/spill paths out of inventory.
10. Version schema/provider semantics explicitly. Do not mix different digest algorithms or change compatibility implicitly.

Before publishing: `./gradlew test installDist distTar` and `python3 scripts/process-smoke.py`. Add regression tests for fixes. Test and inspect the packaged launchers, not only API methods. Keep GitHub Actions permissions minimal. Do not claim benchmarks, platform support, test execution or CI success without actual evidence.
