# Disk and VM image analysis

## Separate, read-only phase

`images` analyzes image candidates in an existing named filesystem inventory. It requires completed discovery, not ordinary duplicate hashing or archive analysis. Neither `scan`, ordinary `resume`, nor `archives` starts it implicitly. Repeat `images` to continue interrupted image work.

The coordinator/API/CLI/storage are Groovy. A bounded Python bridge uses QEMU and libguestfs. QEMU exposes a read-only userspace NBD socket; libguestfs reads that socket explicitly as raw storage. No kernel NBD device, host filesystem mount, source conversion, or root execution is needed. libguestfs starts its own **trusted inspection appliance**, not the source disk's guest operating system. No guest programs are executed.

The current/default visible disk state is inspected. There is no snapshot switching, repair, guest boot, deleted-file recovery, or unallocated-space analysis. Ordinary guest files stream through a pipe into SHA-256; they are not copied to host temporary files. Filesystem types and metadata support depend on the installed appliance.

## Runtime

Ubuntu 24.04, Java 21, Linux x86-64 are the verification target. Install native components:

```bash
sudo apt-get update
sudo apt-get install -y qemu-utils python3-guestfs libguestfs-tools
./gradlew test installDist
libguestfs-test-tool
```

The system Python `/usr/bin/python3` must be able to import `guestfs`; no pip packages are needed. Run the application as a regular user, **not with sudo**. Landlock support is required: a kernel or container security profile that makes it unavailable causes a fail-closed capability error, not unconfined decoding.

Supermin must have an installed host kernel image/modules and permission to read that kernel. CI installs `linux-image-generic` and makes its `/boot/vmlinuz-*` files readable before running tests unprivileged. On a minimal server, install an appropriate kernel package and resolve the kernel-readability error reported by `libguestfs-test-tool`; do not work around it by running the scanner as root.

KVM is optional. `--image-acceleration auto` lets libguestfs choose; `tcg` forces software emulation and `kvm` requires available KVM access. The bridge deliberately does not inherit arbitrary libguestfs/QEMU environment overrides.

## Commands

```bash
DB="$HOME/scans.duckdb"
./bin/fnord-dedup2 --db "$DB" scan --name "vm-01" --root /data/vms --discover-only
./bin/fnord-dedup2 --db "$DB" images --name "vm-01" --image-temp /fast-storage/image-temp

./bin/fnord-dedup2 --db "$DB" image-status --name "vm-01"
./bin/fnord-dedup2 --db "$DB" image-list --name "vm-01"
./bin/fnord-dedup2 --db "$DB" image-errors --name "vm-01"

# Use a result_id returned by image-list.
./bin/fnord-dedup2 --db "$DB" image-filesystems --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-partitions --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-components --result RESULT_UUID
./bin/fnord-dedup2 --db "$DB" image-entries --result RESULT_UUID > guest-files.jsonl
```

Status/work summaries are JSON; row reports are JSON Lines. Progress is on stderr. `--quiet` suppresses progress. Counts named `entries_written_this_run` are committed entries in the current image attempt, not a whole-scan completion percentage. The same exclusive database lock applies to all commands: stop/close analysis before reporting from a different process.

## Formats and deliberate boundaries

The filename families are `.vmdk`, `.qcow`, `.qcow2`, `.vdi`, `.vhd`/`.vpc`, `.vhdx`, `.qed`, `.img`, `.raw`, `.dd`, `.iso`, and `.dmg`. The explicit driver is selected from this mapping and native parsing validates it. A misleading extension fails rather than silently trying a different disk driver. RAW/IMG/DD and optical ISO are passed explicitly as raw block storage, never content-autodetected into an image driver.

QCOW1/2 and QED backing chains are supported through approved explicit block graphs. VMDK monolithic sparse, flat descriptor/extent, split sparse/flat, and stream-optimized layouts use validated descriptor references. Physical extents are component records, not independent guest disks. Backing disks can also be independently analyzed when they are themselves candidates.

Differencing VDI/VHD/VHDX, VHDX parent locators, encrypted container/filesystem data, QCOW2 external data files, and unrecognized VMDK extent layouts currently receive explicit errors. They are **not** treated as zero-filled self-contained disks. This is not a claim to support every variant of the requested formats.

DMG uses QEMU's direct read-only DMG driver. There is no conversion fallback. A supported DMG compression method does not imply that every inner filesystem is readable: APFS and other unavailable filesystems produce errors. ISO9660/Joliet/Rock Ridge/UDF visibility depends on the native filesystem stack. OS detection is not required; data-only disks and filesystems without a partition table are valid inputs.

The file hash covers the main logical byte stream, including logical zeros of sparse guest files. Alternate data streams, resource forks, extended attributes, permissions, inode/link-count metadata and forensic integrity verification are outside this phase. Hard-linked names are ordinary file paths, not deduplicated physical storage. Links and special files are recorded but never read as ordinary content.

## Dependencies and read-only enforcement

Every reference must resolve to exactly one regular file already present in the saved inventory. Absolute paths, URI/protocol references, symlink paths, root escape, missing components and cycles are rejected before the decoder opens the disk. A bounded Groovy header reader checks reference-bearing metadata; it is not a replacement disk decoder. An unknown backing format must be rejected rather than guessed as raw.

The QEMU image decoder receives a Landlock filesystem ruleset allowing only approved source files, runtime libraries, and its private work directory. Sources are read-only; private temporary output is writable. TCP access is denied when supported by the kernel's Landlock ABI. The QEMU backing graph explicitly pins each approved format rather than allowing unrestricted automatic backing-file resolution. VMDK extent paths are separately validated.

libguestfs mounts read-only. Ext3/ext4 journal loading, XFS recovery and Btrfs log replay are explicitly disabled. The source layer remains read-only even if a filesystem driver cannot mount safely. There is no automatic filesystem repair or journal fixup.

These controls are not a promise against all native-decoder/kernel vulnerabilities or hostile concurrent replacement of a trusted source tree. Use stable local images or read-only snapshots, current native packages, and stronger host isolation for hostile inputs. A live running VM disk is not a consistent snapshot. Source size and modification time are checked before/after reading and again on native failures. A mismatch invalidates the attempt's guest rows and produces `SOURCE_CHANGED`.

## Exact image reuse

Container reuse is based on ordered component byte hashes and dependency topology, not virtual size or similarity of contained files. A matching overlay with a different backing image must not reuse the same result. The versioned fingerprint covers roles, parent ordinals, explicit formats, lengths and component SHA-256 values. Provider/policy identity must also match. Only clean finalized results are reusable in this version; partial, encrypted, missing-dependency, operationally failed and capped results are not.

`--image-container-hash` controls the cost of hashing large physical image files:

| Mode | Behavior |
| --- | --- |
| `candidate` (default) | Full source/component hashes only when another known image has a matching primary format and file length. This is a cheap candidate filter, never proof of equality. |
| `always` | Hash every required physical component before inspection. Best opportunity for reuse by future scans. |
| `never` | Skip physical-container fingerprinting/reuse; still hash every readable guest file. |

An earlier unique image analyzed without a physical fingerprint cannot immediately serve as a cache hit when another copy appears later. It may be inspected once more before a reusable fingerprinted result exists. Candidate matching is conservative and can miss equivalent differently named/encoded containers; it cannot prove duplicates without full hashes.

Copies reference an immutable result instead of duplicating millions of guest rows. `image-components` describes the canonical result's original source provenance; an alias's own root path remains in `image-list`. Aggregate member/byte counts count unique reachable results, not multiplied aliases or physically reclaimable bytes. Cross-filesystem/archive/image file duplicate reporting is not added to the existing `duplicates` command.

## Results, errors, and restart

Run phases: `IDENTIFYING`, `ANALYZING`, `PAUSED`, `COMPLETE`, `COMPLETE_WITH_ERRORS`. Job states: `PENDING`, `RUNNING`, `COMPLETE`, `PARTIAL`, `COMPONENT`. These are durable work states, not process-liveness claims. Error codes/categories explain invalid, missing, encrypted, unmountable, unsupported, limited and operational cases instead of overloading one validity Boolean.

Each attempt has a UUID result. Bounded Appender transactions may commit while it is `RUNNING`, but result-entry APIs refuse that result until finalization. Recovery removes unfinished result rows and replays the image from the beginning. Already finalized results are immutable and preserved. A damaged/unmountable filesystem does not erase successfully read sibling filesystems. Only a regular file with complete size-checked `READ_OK` data receives a normal `sha256`; failed reads do not.

Press Ctrl+C or send SIGTERM, then repeat `images --name ...`. SIGKILL cannot do graceful cleanup; the native supervisor terminates its process group after controller death and releases its leases before stale temporary work can be deleted. A very immediate restart can report that a lease is still held; retry after that helper has exited rather than deleting control files manually.

Operational failures retry on the next image invocation. `--retry-errors` reattempts terminal partial jobs. `--force` reanalyzes roots and bypasses cache reuse; old immutable historical results remain stored. There is no historical-result garbage collector. Adding a missing extent or changing a disk requires a **new named filesystem scan**, not a refresh of the old inventory.

Exit codes remain 0 success, 1 invocation/configuration failure, 2 CLI usage, 3 recorded image errors, and 130 cooperative cancellation. OS signals may produce signal-derived statuses.

## Resources and temporary storage

Only one NBD decoder and one inspection appliance are active for the image being processed. Temporary storage must be outside the scan root; default `<database>.images-tmp`. Private owned/leased attempt directories hold appliance scratch, a shared per-invocation appliance cache, metadata queues and bounded protocol files. Payload work is removed on completion, errors, cancellation, or recovery. Ownership/namespace controls may remain.

| Option | Default |
| --- | ---: |
| `--image-max-files` | 5,000,000 entries per image |
| `--image-max-components` | 256 |
| `--image-max-temp-bytes` | 1,099,511,627,776 bytes (1 TiB) |
| `--image-max-listing-bytes` | 268,435,456 bytes (256 MiB) |
| `--image-temp-min-free` | 5,368,709,120 bytes (5 GiB) |
| `--image-timeout-seconds` | 14,400 |
| `--image-native-memory-bytes` | 4,294,967,296 bytes |
| `--image-appliance-memory-mib` | 768 |
| `--image-acceleration` | `auto` |
| `--python` | `/usr/bin/python3` |

Byte limits take integers without suffixes. Native address-space limits apply per native process, not to total Java + DuckDB + helper + appliance RSS. Free-space polling is not a reservation against other processes; host quotas are needed for a hard host-wide boundary. Temporary accounting includes logical lengths of sparse scratch files, conservatively. No full-image copy/conversion is performed by the current provider. No hardware throughput claim is made.

## Groovy API

```groovy
import fnord.dedup.Dedup
import fnord.dedup.image.ImageOptions
import java.nio.file.Path

Dedup.open(Path.of('/data/scans.duckdb')).withCloseable { d ->
    d.analyzeImages('vm-01', new ImageOptions(
        tempDirectory: Path.of('/fast-storage/image-temp'),
        containerHash: 'always'
    ))
    String result = null
    d.eachImage('vm-01') { image ->
        if (result == null && image.state == 'COMPLETE') result = image.result_id
    }
    // Streaming callbacks cannot issue reentrant queries on this engine.
    if (result != null) d.eachImageEntry(result) { entry -> println entry }
}
```

`examples/images.groovy` includes signal handling and JSON output, runnable with the installed `fnord-dedup2-groovy` launcher. Public operations are synchronous. Only `StopToken` is intended for cross-thread cancellation.

## Not implemented in this phase

Nested archive-to-image, image-to-archive and image-to-image dispatch; optional OS inventory; multi-disk RAID/VM assembly; historical snapshots; password/key management; permanent conversion; image repair; automatic deletion; combined file duplicate-report commands. The provider and result boundaries permit these extensions, but there are no placeholder flags that silently pretend to perform them.

## Verification

Run existing filesystem/archive suites as well as:

```bash
./gradlew test installDist distTar
/usr/bin/python3 scripts/image-smoke.py
/usr/bin/python3 scripts/image-extra-smoke.py
```

The native suites require data-fixture tooling (`genisoimage`, `e2fsprogs`, `ntfs-3g`, `dosfstools`) and a working libguestfs appliance, and must run unprivileged. Image fixtures are generated under temporary test directories. Unit tests cover fake-provider failure boundaries and actual DuckDB persistence. Native tests are necessary for format/codec/platform claims; a mock-provider success is not a native format test.

References: QEMU image tools https://www.qemu.org/docs/master/tools/qemu-img.html ; libguestfs API https://libguestfs.org/guestfs.3.html ; libguestfs security https://libguestfs.org/guestfs-security.1.html ; Linux Landlock https://docs.kernel.org/userspace-api/landlock.html .
