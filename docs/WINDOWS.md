# Windows core support

## Scope and runtime

The core workflow supports 64-bit Windows with Java 21: filesystem discovery, SHA-256 hashing, single-scan duplicate reporting, cross-scan verification, database merge, CLI and Groovy scripting. The native CI target is Windows Server 2025 with Temurin 21; this does not establish coverage of every Windows edition, filesystem or network provider.

**Archive extraction and disk/VM-image analysis are not supported on Windows.** Their public processing APIs refuse immediately, before creating feature schemas or starting helpers. Their existing Linux implementations remain available. Opening/reporting/merging a database that already contains those tables does not require their native tools.

Windows runtime needs the application distribution and Java 21. It does not require WSL, Cygwin, Git Bash, Python, libarchive, QEMU, libguestfs or a separate Groovy installation. Building additionally needs PowerShell and network access for the pinned Gradle/dependencies.

## Build and run

From a PowerShell checkout:

```powershell
.\gradlew.bat test installDist distZip
.\bin\fnord-dedup2.bat --help

$DB = "$HOME\fnord-scans.duckdb"
.\bin\fnord-dedup2.bat --db $DB scan --name "Photos" --root "D:\Photos"
.\bin\fnord-dedup2.bat --db $DB duplicates --name "Photos" --format jsonl
```

The Gradle 8.14.5 bootstrap verifies the pinned SHA-256 before extraction. No curl, unzip or bash is needed on Windows. The distribution includes `bin\fnord-dedup2.bat` and `bin\fnord-dedup2-groovy.bat`; a downloaded ZIP can be unpacked without installing Gradle. `JAVA_HOME` or `java` on PATH must point to the appropriate Java runtime.

The database and its sidecars should preferably be on a reliable local disk, outside the scanned tree. Core commands retain their one-process-per-database locking rule.

## Portable persisted and reported paths

| Native input | Stored representation |
| --- | --- |
| `C:\Data\Photos` | `C:/Data/Photos` |
| `C:\` | `C:/` |
| `\\server\share\photos` | `//server/share/photos` |
| `folder\file.txt` below a Windows root | `folder/file.txt` |

Relative paths are serialized from native path components. Only an explicitly Windows lexical representation is allowed to convert native backslashes. A real Linux filename such as `a\b.txt` remains one filename component with a literal backslash. No global backslash replacement is performed on arbitrary database text.

`StoredPath` distinguishes native I/O from stored identity. It validates relative components, drive/UNC roots and logical display joins without using the current platform to reinterpret a foreign root. Root and relative-path spelling/case and Unicode are retained; drive letters are normalized when recording native Windows roots. The explicit root is resolved once to its real target. Ordinary relative CLI roots are resolved against the working directory. Ambiguous drive-relative/current-drive-rooted forms (`C:folder`, `C:`, `\folder`) are rejected.

Normal extended drive/UNC syntax can be accepted where the JDK accepts it and is persisted without the API prefix. Physical devices, volume-GUID namespaces, ADS syntax and device/reserved names are not scan roots. Names requiring ambiguous Win32 trailing-dot/space semantics are refused rather than silently normalized. Unsupported descendant names are recorded as discovery errors; supported source names are never renamed or sanitized.

Runtime paths for the actual DuckDB, WAL, lock and spill files remain native OS paths. Native path comparisons and existing-file identity checks prevent case/alias spelling from exposing the active database as scan input.

## Foreign-platform databases

A Windows-created database can be opened on Linux for metadata, reports, merge and cross-scan comparisons using stored hashes, and conversely for a Linux-created database on Windows. Stored roots are historical provenance, not a relocation instruction.

On Linux, `C:/Data` must never become a relative path beneath the working directory. On Windows, `/data` must never mean the current drive's `data` directory. Missing hashes for foreign roots stay unresolved with `FOREIGN_ROOT`; already completed hashes are reused without reopening or statting those source files. Available candidates from other selected scans still continue. Discovery refuses foreign root access before altering its saved replay checkpoint.

Reports always use portable paths, including on Windows. Database merge copies stored roots and relative paths exactly; its audit accepts drive, UNC and POSIX identities without opening their source trees. The base schema version is unchanged. Existing canonical Linux databases need no conversion. Experimental Windows databases with backslash-separated relative paths are not silently migrated.

## Filesystem safety and limitations

Only safely identified regular files are hashed. Windows junctions can report both directory and other attributes, so the scanner tests symbolic-link/other classifications before directory/regular-file classifications. Descendant junctions, symlinks and opaque reparse entries are recorded but not traversed; Windows ancestors are checked again before queued traversal and file hashing. An explicitly selected root alias is resolved once to its canonical target.

The policy is conservative for cloud placeholders and other reparse mechanisms: they are not automatically hydrated or followed. There is no claim to eliminate every hostile concurrent path-replacement race. Scan quiescent trees/snapshots for stronger guarantees.

NTFS alternate data streams, VSS snapshots, ACL/SID collection, Windows file-ID tracking, USN Journal integration and physical-device scanning are out of scope. Hardlinked names remain separate observations; physical reclaimable space is not inferred.

Locked files may produce access/sharing errors. Their metadata remains inventoried, no partial hash is saved, unrelated files continue, and a later retry can succeed when the lock is released. No antivirus/security settings are bypassed. Modification seconds/nanoseconds preserve the precision exposed by the filesystem; the application does not invent nanoseconds or assume every filesystem supports the same precision.

There is no application-level 260-character path limit. Actual creation/opening remains subject to the JDK, Windows policy and filesystem. UNC roots are represented and resolved natively, but successful local tests are not a claim that every SMB server, credential setup or network failure mode was exercised. Mapped drives are stored as `Z:/...`, not automatically rewritten to UNC.

## Cross-scan and scripts

```powershell
.\bin\fnord-dedup2.bat --db $DB cross-duplicates `
    --scan "Laptop" --scan "NAS" --workers 2 --format jsonl

.\build\install\fnord-dedup2\bin\fnord-dedup2-groovy.bat `
    examples\cross-duplicates.groovy $DB "Laptop" "NAS"
```

Hash selection, committed-hash reuse, bounded workers and JSON output semantics match Linux. Cross-scan verification does not change the underlying scan phases. Use quoted PowerShell arguments for spaces. Existing `.zip`, `.vmdk`, etc. files can still be ordinary filesystem entries hashed as whole files; their contents are not analyzed by core commands.

## Database merge

```powershell
.\build\install\fnord-dedup2\bin\fnord-dedup2-groovy.bat `
    scripts\merge-database.groovy `
    --source "D:\Imports\source.duckdb" `
    --destination "D:\Fnord\master.duckdb" --dry-run
```

Remove `--dry-run` to import. Both database files must exist and be closed by other application processes. Same-file checks and sidecar locks use native filesystem identity, including case aliases. All normal name-conflict, remapping and all-or-nothing destination transaction rules remain in force. Merge neither scans nor relocates the paths stored inside either database.

## Stop and recovery

Ctrl+C uses the existing JVM shutdown hook and StopToken cooperative cancellation. Re-run `resume --name ...` for discovery/ordinary hashing; repeat `cross-duplicates` for a selected cross-scan comparison. Valid completed hashes may commit; incomplete computations do not become hash rows. Keep any WAL with its database.

Forced Windows process termination skips normal cleanup. Recovery relies on DuckDB transaction recovery and the same durable scan checkpoints. Process tests explicitly force termination during discovery, ordinary hashing, cross-scan hashing and an uncommitted database merge, then reopen and verify retry/rollback. Cooperative StopToken tests are separate from a manual terminal's Ctrl+C delivery; do not interpret force-termination tests as an interactive-console test.

## Verification

```powershell
.\gradlew.bat test installDist distZip
.\scripts\windows-core-smoke.ps1
.\scripts\windows-smoke.ps1
```

`windowsCoreTest` remains available as an explicit Gradle alias. The Windows lane runs portable/core tests and native junction, sharing-lock, Unicode, case-alias, long-path and recovery tests. POSIX-only fixtures and native archive/image suites remain in Linux CI rather than requiring Linux tools on Windows. The ZIP is also unpacked into a path containing spaces and its launcher exercised.

No source files are deleted, moved, rewritten or hardlinked by the application. Files created/removed by the test suite are generated fixtures only.
