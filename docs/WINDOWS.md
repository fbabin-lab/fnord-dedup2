# Windows core support

## Scope

The core duplicate-file workflow supports Windows with Java 21: filesystem discovery, SHA-256 hashing, single-scan duplicate reporting, cross-scan verification, database merge, CLI and Groovy scripting. Archive analysis and disk/VM-image analysis remain Linux-only and are not part of Windows compatibility.

Windows runtime does not require WSL, Cygwin, Git Bash, Python, libarchive, QEMU, libguestfs or a separate Groovy installation.

## Build and run

PowerShell examples:

    .\gradlew.bat windowsCoreTest installDist
    .\bin\fnord-dedup2.bat --help
    $DB = "$HOME\fnord-scans.duckdb"
    .\bin\fnord-dedup2.bat --db $DB scan --name "Photos" --root "D:\Photos"
    .\bin\fnord-dedup2.bat --db $DB duplicates --name "Photos" --format jsonl

The checksum-pinned gradlew.bat bootstrap downloads Gradle 8.14.5 with PowerShell, verifies its SHA-256, and does not require curl, unzip or bash. The installed distribution contains fnord-dedup2.bat and fnord-dedup2-groovy.bat.

## Portable stored paths

Database paths representing scanned content always use / separators. C:\Data\Photos is stored as C:/Data/Photos, C:\ as C:/, and \\server\share\photos as //server/share/photos. Relative inventory paths are stored as folder/file.txt.

Serialization operates on Path components. The application never globally replaces backslashes because Linux permits a literal backslash inside one filename.

Runtime paths for DuckDB, WAL, lock and temporary storage remain native host paths. Drive-relative roots such as C:folder and device/volume namespace roots are rejected. Normal drive roots, absolute directories and UNC shares are supported.

## Databases moved between operating systems

A Windows-created DuckDB can be opened on Linux for metadata, reports, merge and cross-scan comparisons that already have the needed hashes. A Linux-created database can likewise be opened on Windows.

The application never reinterprets a foreign root as local. On Linux, C:/Data is not treated as a relative path. On Windows, /data is not mapped to the current drive. If a missing hash requires bytes from a foreign-platform root, that candidate remains unresolved with FOREIGN_ROOT. Existing completed hashes remain usable.

Database merge copies stored roots and relative paths exactly and does not rewrite them for the machine performing the merge.

## Filesystem behavior

Only regular files are hashed. Symbolic links and redirecting reparse entries must not be recursively followed as ordinary directories. Windows directory junction behavior is covered by the Windows CI fixture.

NTFS Alternate Data Streams, VSS snapshots, ACL/SID collection, Windows file IDs, USN Journal integration and physical-device scanning are outside this version.

Locked files may fail with access or sharing errors. Their inventory remains, no hash is stored, other files continue, and a later retry can succeed after the other application releases the file.

Mapped drives are stored as their drive path, for example Z:/Backup; they are not automatically rewritten to UNC form. The application imposes no 260-character MAX_PATH limit of its own, although actual support depends on Java, Windows policy and the filesystem.

## Cross-scan and duplicate output

Report paths remain portable even on Windows, for example D:/Photos/2026/a.jpg. Cross-scan candidate selection and hashing are otherwise identical to Linux, and existing hashes are reused without reopening files.

## Database merge

Use native Windows paths for database files. Example:

    build\install\fnord-dedup2\bin\fnord-dedup2-groovy.bat scripts\merge-database.groovy --source "D:\Imports\source.duckdb" --destination "D:\Fnord\master.duckdb" --dry-run

Same-file detection and sidecar locking use native filesystem semantics. Stored scan paths are copied unchanged.

## Testing

Windows CI runs only the core compatibility lane:

    .\gradlew.bat windowsCoreTest installDist distZip
    .\scripts\windows-core-smoke.ps1

Linux CI remains authoritative for archive and disk-image functionality.
