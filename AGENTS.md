# Contributor / AI instructions

Read README.md, REQUIREMENTS.md, docs/ARCHITECTURE.md, src/main/resources/schema.sql and relevant tests before changes. For archive work also read docs/ARCHIVES.md, docs/ARCHIVE_SPEC.md and src/main/resources/archive/schema.sql. For image work read docs/IMAGES.md and src/main/resources/image/schema.sql. Preserve the original LICENSE. Do not modify fnord-dedup or other repositories as part of this standalone project.

Use Groovy for application code, Java 21, and pinned dependencies. Keep hot loops statically compiled where practical. Keep CLI presentation separate from the scripting API, traversal, storage and hash providers. Do not add a server/framework for a local CLI problem. Python helpers are narrow structured native bridges, not owners of JDBC or scan orchestration.

Critical filesystem invariants:

1. Never delete, move, hardlink or rewrite source files. Never execute filenames or untrusted values through a shell.
2. Discovery finishes before ordinary hashing starts. Ordinary hash candidates are regular files in a repeated-size group within that scan. Archive and image analysis are separately explicit phases; all readable regular members receive full hashes.
3. Persist directory inventory, child work, completions and active checkpoint in one transaction. Partial-parent replay deletes only that parent's immediate-child inventory/work. Children cannot run before parent completion.
4. Only the coordinator owns JDBC. Workers return values. Persist only complete ordinary hashes; do not introduce per-file pending/running checksum tasks.
5. Keep bounded pages/queues/buffers. Do not replace appender ingestion with per-file commits or load entire trees/results into JVM collections. Appenders require an activated native transaction before use: execute SQL after disabling autocommit. Match numeric appends to SQL column types, not incidental JSON types.
6. Retain transactional/WAL durability. Unsafe modes are out of scope unless explicitly requested with documented consequences.
7. Preserve modification-time precision, validate before/after hashing, and record errors. Do not silently report partial scans as complete. Resume is not refresh.
8. No persistent inode, permission/mode, UID/GID, blocks or link-count collection. Internal application IDs are allowed. Transient native attributes may determine entry type without persisting unwanted metadata.
9. Handle unusual UTF-8 filenames safely; never parse filesystem names using lines/whitespace. Keep symlinks and special files out of hashing. Keep DB/WAL/lock/spill paths out of inventory.
10. Version schemas/providers explicitly. Never mix digest algorithms or change compatibility implicitly.

Windows/core portability invariants:

- Core filesystem, hashing, duplicate/cross-scan, merge, CLI and scripting changes must preserve Linux and Windows behavior. Archive/image support remains Linux-only unless explicitly requested.
- Never persist Path.toString() for scan roots or relative filesystem paths. Use the stored-path codec.
- Persisted filesystem paths use / separators. Windows drive roots are C:/...; UNC roots are //server/share/....
- Never globally replace backslash with slash in arbitrary stored names: backslash is a legal Linux filename character. Serialize native path components instead.
- Use native java.nio.file.Path for local I/O and portable strings for database/report identities. Never feed a foreign-platform stored root to the host path provider.
- Database merge copies stored path strings unchanged. Existing hashes from a foreign-platform scan remain valid observations even when the source tree is offline.
- Windows build/runtime must not depend on Unix shell utilities. Keep the checksum-pinned gradlew.bat bootstrap and native .bat launchers working.

Archive invariants:

- Only one native extractor is active. Recurse depth-first through the same processor; retain parent payloads until child work finishes. Temporary data is outside the source tree.
- Native member paths never control output paths. Payload names are numeric ordinals. Never recreate/follow links, FIFOs or devices. Preserve duplicate member names as distinct ordinals. Bound native output, memory, time and temporary bytes.
- RUNNING generations are staging, never authoritative. Flush bounded batches before publication. Recovery removes unfinished generations but retains completed children. Never mutate canonical results referenced by other scans.
- Exact fingerprints represent ordered bytes/volume layout, not semantic equivalence. Scope volume grouping to an immediate logical directory. Do not reuse incomplete/unknown-volume, encrypted, operationally failed or resource-limited results. Provider/policy/depth compatibility is required.
- A member error does not erase good siblings. Damaged recovered bytes may have recovered_sha256 but never normal sha256. Do not mix recovered hashes into confirmed duplicates.
- Cleanup validates ownership and leases, never follows links, preserves controls until payloads are gone, and never cleans another database namespace after copying a database.
- Native helpers terminate on cancellation/controller death. No password prompts or external shell/filter commands. Path hardening is not a full native-code sandbox; document limits honestly.
- Streaming callbacks must not issue reentrant queries on the same engine. Ordinary resume must not opt users into archive extraction.

Image invariants:

- Image analysis is independent of filesystem resume and archive extraction. Never boot the source guest, repair it, or mount it in the host namespace. libguestfs may start its own trusted appliance.
- Native block decoding is read-only behind an approved explicit graph and mandatory Landlock confinement. Never silently fall back to unconfined parsing. RAW/IMG/DD/ISO are explicitly raw storage.
- Resolve every backing/extent through the immutable source inventory. Reject unsafe paths, symlinks, missing dependencies, cycles and unknown backing formats before opening. Do not infer unreadable/missing backing data as zeros.
- Compound identity includes every dependency's bytes and topology. Only compatible clean finalized image results are reusable. Partial/capped/operational results cannot poison the cache.
- Guest files stream into SHA-256 without temporary copies. Do not follow guest links or open special files. Filesystems are independent of OS detection and kept distinct in the schema.
- RUNNING image generations are hidden. Restart replays the current image, preserving finalized results. Revalidate all source components on native failure as well as success; discard guest rows on source changes.
- Supervise and retire the whole native process group before releasing temp/cache leases. No orphan decoder may keep using a directory that cleanup can delete. Limits are not whole-process RSS guarantees; document them precisely.
- No claims of nested image/archive dispatch, password support, differencing VHD/VHDX support or unsupported filesystems merely because the outer format is recognized. See docs/IMAGES.md for boundaries.

Before publishing: `./gradlew test installDist distTar`, `python3 scripts/process-smoke.py`, `python3 scripts/rollback-smoke.py`, and `python3 scripts/archive-smoke.py`. Full archive CI uses `--require-7z --upstream-rar5`. Image changes additionally require `/usr/bin/python3 scripts/image-preflight.py`, `/usr/bin/python3 scripts/image-smoke.py` and `/usr/bin/python3 scripts/image-extra-smoke.py` with a working unprivileged native runtime. Add regressions for fixes and inspect packaged launchers/examples. Keep Actions permissions minimal. Never claim benchmark, platform, test or CI success without actual evidence.
