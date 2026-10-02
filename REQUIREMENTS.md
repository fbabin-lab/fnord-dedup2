# Version 0.1 requirements

The Linux application is implemented in Groovy with equivalent CLI and synchronous scripting entry points. One embedded DuckDB database stores multiple independently named filesystem inventories. No server is required.

## Filesystem phases

Pass one records directory/file paths, filename, type, byte size and modification time without reading contents. Inodes, permissions, ownership, allocated blocks and link counts are intentionally excluded. Pass two hashes only regular files whose size repeats within that scan, then reports matching size and full SHA-256.

Discovery resumes from durable directory work and atomic chunk checkpoints. Hashing persists completed checksums only; interrupted work may be recalculated. Source changes and metadata/read errors must be visible. The source tree is never modified. Resume does not refresh completed directories.

Use bounded appender batches, bounded application memory/concurrency, reusable buffers and analytical DuckDB queries. Preserve WAL/transaction durability. A checksum implementation is replaceable; one native subprocess per file is not required.

Minimum commands: scan, resume, hash, list, status, duplicates, errors. Status/list/work summaries are JSON; large reports stream JSON Lines. Incomplete filesystem reports require explicit opt-in. Validate roots, names, paths and resource limits.

## Optional archive phase

After discovery, explicitly requested archive analysis inspects candidates including unique-size files. A replaceable native provider extracts one archive at a time into private temporary storage. Persist logical member paths, filenames, types, declared/actual sizes, available timestamps and streaming SHA-256 of readable regular files. Source files remain read-only; extracted payloads are disposable.

Support ZIP, TAR/compressed TAR, RAR, 7z and bare compressed-stream families according to native capabilities. Group numeric multipart volumes within one logical directory/container, retain provenance and hashes, detect missing/ambiguous sets, and attempt safe partial recovery. Capability and credential failures must be explicit and noninteractive.

Exact archive bytes/ordered volume sets share compatible immutable canonical results without repeated extraction or duplicated member rows. This includes nested archives and scans in the same database. Incomplete/unknown-volume, encrypted, operationally failed and resource-capped results are not reusable.

Nested archives use the same depth-first pipeline, including multipart grouping. Parent temporary contents remain until children finish. Member errors do not erase good siblings. Damaged recovered bytes may have recovered_sha256 but never a normal confirmed-content hash.

Bounded attempt-generation batches remain non-authoritative until publication. Restart discards unfinished generations and replays the current archive; finalized child results survive. Cleanup validates ownership and leases, avoids links, and handles interruptions during extraction, initialization and cleanup.

Enforce finite configurable depth, member count, native memory, wall-clock, temporary-byte and free-space limits. Provide an optional minimum logical archive-set size; sets below it are intentionally skipped without hashing/extraction or error, multipart sizes are summed, and the same policy applies to nested archives. Archive member names are untrusted metadata, never output paths. Do not recreate/follow links or special files, use shells, or parse human-oriented filename tables. Document native-code isolation limits honestly.

Provide analysis/resume, status, streaming archive/member/volume/error reports, retry and force through CLI/API. Ordinary filesystem resume remains unchanged. Added volumes or source changes require a new named inventory. See docs/ARCHIVE_SPEC.md for the archive implementation contract.

## Optional disk-image phase

After discovery, explicitly requested image analysis recognizes VMDK, QCOW1/QCOW2, VDI, VHD/VHDX, QED, IMG/RAW/DD, ISO and DMG candidate families. The outer container and inner filesystem capabilities are independent; recognition alone is not a validity claim. Unsupported variants and encryption produce structured errors.

Use native QEMU read-only decoding and a trusted libguestfs inspection appliance. Never boot source guests, execute their software, repair their storage, mount guest filesystems in the host namespace, write source images, or require root execution. RAW inputs are explicitly typed; never allow their bytes to switch the native disk driver through auto-detection. One image decoder/appliance is active at a time.

Resolve backing chains/extents against regular files in the saved source inventory before native opening. Reject unsafe/absolute/protocol paths, symlinks, root escapes, missing components, cycles and unidentified backing formats. Native image decoding must remain confined; absence of required isolation is an error, not permission to fall back to unrestricted access. Differencing VHD/VHDX/VDI, external QCOW2 data and unsupported layouts must not be silently interpreted as complete disks.

Discover partitions and filesystems independently of OS detection, including data-only and filesystem-only raw images. Scan each accessible filesystem separately without following links or opening special files. Record exact logical paths, filenames, types, sizes and available modification time. Stream every readable regular guest file into SHA-256 without a host temporary copy. Normal hashes require complete, size-checked READ_OK bytes. Unreadable/corrupt filesystems or members must not erase good independent results.

Exact image fingerprints cover all ordered physical component bytes and dependency topology plus compatible provider/policy identity. An overlay alone is insufficient. Full physical hashing may be candidate-gated, always requested, or disabled; guest-file hashing remains enabled. Reuse only compatible clean finalized results; preserve source aliases without copying millions of guest entries.

Use independent versioned image tables and bounded Appender generations. RUNNING result rows remain hidden from authoritative APIs. Restart discards unfinished generation rows and replays the current image while retaining finalized results. Validate source size/time before and after processing, including native failures; invalidate guest rows when sources change. Supervisor/process-group cleanup must retire native workers before releasing temporary leases.

CLI/API must expose images, image-status, image-list, image-errors and result-based filesystem/partition/component/entry reports. Repeating images resumes this phase; neither ordinary resume nor archives implicitly invokes it. Provide explicit retry/force, finite resource limits and private temporary storage outside the source tree. See docs/IMAGES.md for runtime dependencies, supported variants, exact defaults, retry semantics and remaining scope.

## Verification and non-goals

Verify real persistence/reopening, scan isolation, changed files, unusual names, non-regular files, batched publication, corruption/partial results, canonical identity, limits and cleanup. Use actual SIGTERM/SIGKILL tests on packaged launchers. Native format/platform claims require native fixtures, not mock-provider success. Source-image hashes before/after inspection must match. Test writes stay within generated fixtures.

Non-goals: destructive deduplication, physical-space/hardlink accounting, a web interface, multiprocess DuckDB writers, atomic source snapshots, continuous watching, metadata refresh, ordinary cross-scan duplicate grouping, historical-result garbage collection, archive repair/recompression/password management or persistent extracted payloads.

Image non-goals for this phase: nested archive/image dispatch, image-to-image recursion, deleted-file carving, guest execution/repair, historical snapshot traversal, OS/application inventory, multi-disk VM/RAID assembly, decryption credentials, permanent conversion and DMG fallback conversion. Combined filesystem/archive/image-member duplicate reports are a separate extension. Do not expose flags that pretend to implement absent capabilities.
