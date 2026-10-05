# Scanned archive browsing and separate duplicate accounting

The WebUI treats a scanned archive as a virtual directory backed entirely by the existing scanner database. No archive extraction, source-file opening, download, deletion, rewriting, or new scanner migration occurs during browsing. Saved content signatures still live only in the separately locked Web state database.

## Navigation and member identity

In **File Explorer**, an analyzed archive has a **Browse archive** action. Selecting a browsable archive filename opens its recorded contents; **File details** still opens the physical file's metadata. The physical file's detail panel also links to its archive contents. Any recorded multipart volume links to the same root archive job rather than creating another logical archive occurrence.

Virtual directories are derived from stored internal paths, even when the archive does not contain explicit directory entries. Empty explicitly recorded directories are retained. Nested archives have their own **Browse nested archive** action. Breadcrumbs cross physical-folder, archive, nested-archive and virtual-folder boundaries. Search matches internal paths/names within the current archive result and virtual-folder prefix; it does not implicitly search inside nested results. Open the nested archive to search there.

Routes have the form `#/scans/{scanId}/archive/{rootEntryId}?chain=3.12&path=documents&member=7`. The root is identified by the archive job's first entry ID. Each chain component is a nested edge's source member ordinal, not a filename. Members use their final-result ordinal, so two entries with the same filename and internal path remain distinct. Canonical result UUIDs are not accepted as unscoped public locations. Names are encoded as URL data and escaped by Angular; they are never interpreted as host paths.

The browser displays actual/declared sizes, timestamps (or Unknown), full normal SHA-256 when eligible, integrity, partial/skipped/unfinished status, diagnostics, and recovered hashes explicitly excluded from duplicate matching. Red signature and amber duplicate signals apply to archive members too. Signatures can be added from member rows/details only when the server finds complete saved evidence. A matching signature flags every logical copy, including a singleton, without creating direct-deletion actions.

## Accounting contract

A content identity is `(SHA-256, actual logical size)`. The new paired summary has two independent domains:

| Domain | Candidate count | Byte measure | Cleanup meaning |
| --- | --- | --- | --- |
| Filesystem | `filesystem.candidateFiles` | `filesystem.candidateBytes`: sum of recorded file sizes | Historical duplicate observations, including retained copies. Not automatically removable. |
| Archive member | `archive.candidateMembers` | `archive.candidateLogicalBytes`: sum of actual, uncompressed member sizes | Analysis-only; `archive.directCleanupBytes` is always `"0"`. |

All matching observations are counted, including possible keepers. These are **not** `(occurrences - 1)` savings estimates. A single ordinary file matching one archive member contributes one filesystem candidate and one archive candidate. The filesystem-only Scenario Builder does not remove that single ordinary file merely because an archive copy exists.

Example: one 5-byte filesystem file and six matching 5-byte archive occurrences produce filesystem count **1 / 5 bytes**, archive count **6 / 30 logical bytes**, and **0 directly removable archive-member bytes**. This does not claim 35 bytes of disk savings.

Byte arithmetic uses DuckDB HUGEINT or Java BigInteger and returns exact decimal strings; Angular formats bytes through BigInt. Zero-byte members remain valid evidence. Nested archive containers and their expanded members are separate observations, as are physical outer archive files and their contents. Do not add these measures as independent disk savings: they overlap physically, compression ratios differ, and archive rewriting is not implemented.

Canonical results can be reused by multiple root archives, multiple nested placements, or different scans. Summaries expand the published result graph by **logical location**, not by unique result UUID. One multipart job counts once, whereas two distinct root archive copies count twice. A canonical result's original `scan_id` is not the occurrence scan; selected roots and nested edges determine provenance.

## Eligibility, scope and incomplete data

Confirmed member evidence requires `kind=FILE`, `integrity=READ_OK`, no encryption, a nonnegative actual size, and a complete lowercase SHA-256. Damaged, recovered-only, unreadable, missing-size, encrypted and metadata-only entries remain unresolved. A recovered digest never confirms a duplicate or creates a signature. Only reachable finalized COMPLETE/PARTIAL attempts contribute evidence. RUNNING staging and orphaned results are excluded. Successfully read members in PARTIAL archives remain eligible; the archive warning and unresolved coverage remain visible. SKIPPED jobs are visible but do not imply complete contents.

Dashboard and scan detail show paired accounting for all scans and one scan respectively. Explorer/member-details signals and reference-based occurrence lists are database-wide. **Duplicate Explorer's archive-analysis panel uses the last applied explicit scan selection only.** Filename, directory, extension, time, error and cross-scan-only filters of the existing filesystem panel do not apply to the separate archive panel; its scope note says so. No selection never becomes an all-scans archive group query.

The existing `/duplicates/groups`, filesystem search filters and Scenario Builder remain filesystem-only. Archive-analysis rows do not offer KEEP/REMOVE planning. A physical file's detail panel separately links to its matching archive members; archive member details separately page filesystem and archive occurrences. The Signature Store's existing **Find matches** list remains a filesystem list; archive members display and can create/edit the same shared signatures through the archive browser.

## Read-only API

All routes use `/api/v1`. Normal structured errors and scanner sidecar locking still apply.

| Method and route | Contract |
| --- | --- |
| `GET /archives/summary` | Paired database-wide counts, bytes and archive coverage. |
| `GET /scans/{scanId}/archives/summary` | Paired one-scan summary. |
| `GET /scans/{scanId}/entries/{entryId}/archive` | `chain`, `path`, `search`, `limit`, `cursor`; virtual children or recursive-in-this-result search. |
| `GET /scans/{scanId}/entries/{entryId}/archive/members/{ordinal}` | `chain`; member details and split all-scan counts. |
| `GET /scans/{scanId}/entries/{entryId}/archive/members/{ordinal}/occurrences` | `chain`, explicit `storageKind`, `limit`, `cursor`; all-scan matches from server-derived saved member evidence. |
| `GET /scans/{scanId}/entries/{entryId}/archive-occurrences` | `limit`, `cursor`; all-scan archive matches from a saved filesystem file. |
| `POST /scans/{scanId}/entries/{entryId}/archive/members/{ordinal}/signature` | `chain`; body `{tag?, memo?}` only. Size/hash come from the recorded member, never the request body. |
| `POST /archives/duplicates` | Body `{scanIds, limit?, cursor?}`. Only confirmed groups containing at least one archive member. |
| `POST /archives/occurrences` | Body `{scanIds, size, sha256, storageKind, limit?, cursor?}`. `storageKind` is explicitly FILESYSTEM or ARCHIVE_MEMBER. |

Pages are bounded at 500 rows, default 100; group UI pages are 50. Directory cursors bind scan, root, canonical root/current result, chain, prefix and search. Duplicate/occurrence cursors bind content, selected scans, storage category and ordering. Queries use bound parameters, a 30-second statement timeout and the configured DuckDB memory cap. No full tree or complete occurrence list is materialized in application memory. Graphs deeper than 64 nested edges or cyclic published ancestry return `ARCHIVE_GRAPH_LIMIT` rather than silently reporting complete totals. Missing archive schemas return zero/unavailable accounting and an explicit unavailable response for browsing.

## Verification

`ArchiveServiceTest` covers split accounting, canonical aliases across scans, multipart aliases, nested identities, repeated paths, non-BMP Unicode paths, folder/member paging, scope-bound cursors, damaged and staging exclusions, incomplete coverage, zero/huge bytes, signature lifecycle, no archive members in cleanup snapshots, missing schema, cycle rejection and native nested ZIP browsing with the generated source directory removed.

`web-ui/scripts/archive-smoke.mjs` runs from `duplicates-smoke.mjs` against the packaged application. It creates and scans actual nested/repeated ZIP fixtures, removes only the generated source directory, and checks the read-only HTTP and browser flow, signature updates, links, pagination/search, separate analysis and desktop/mobile layouts. Scanner file SHA-256 must be unchanged after all web requests. Existing CLI/native process suites remain unchanged.
