# Scenario Builder

Scenarios are saved retention plans over historical filesystem inventory. They use complete persisted size and SHA-256 evidence from explicitly selected scans. They never read a source file, calculate a missing hash, change scanner data, or execute a KEEP/REMOVE decision. Archive members and disk-image guest entries are excluded. Validated snapshots can be exported as [JSON, JSONL, and CSV cleanup manifests](SCENARIO_EXPORTS.md).

## Scope and definitions

Create a scenario from Scenario Builder, a scan overview, a directory, File Search, Duplicate Explorer filters, or a content group's reference file. Select 1–1,000 scans. There is no implicit all-scans scope. A saved search can seed common filters after selecting an explicit scope; an un-hashed or not-confirmed search cannot seed a confirmed-content plan. Directory search conversion preserves its single-scan scope.

The configuration uses the Duplicate Explorer request: `scanIds`, `mode`, `minOccurrences`, `minScans`, optional name/path/extension/size/modified/error filters, a recorded `directory`, and an optional recorded reference `entry`. Filters qualify whole content groups; generated decisions include every confirmed observation of each qualifying group in the selected scans. Occurrences outside the target filters remain KEEP. Incomplete discovery blocks generation. Incomplete hashes remain visible in coverage and never become confirmed candidates.

Definitions have a UUID, scanner database identity, name, description, revision, timestamps, status, normalized configuration, manual choices, and optional generated snapshot. Names are unique per configured scanner database. Saved search definitions are copied into the scenario; later edits to the saved search do not change it.

## Decision precedence

1. Protected recorded paths and their descendants remain KEEP. Paths outside the target filters remain KEEP. Optional protection scan IDs must belong to the selected scans; omitting the ID applies the protection to each selected scan. An empty relative path protects the entire selected scope.
2. Conflicting history, an un-hashed alias, recorded errors, or no confirmed keeper prevent automatic removal. These candidates are UNRESOLVED. Windows drive/UNC identities fold case conservatively; POSIX identities preserve case and literal backslashes.
3. Manual mode leaves eligible target paths UNDECIDED. Otherwise ordered retention rules choose a keeper: `PREFER_SCAN`, `PREFER_PATH` (exact path or descendants, optional scan), `NEWEST`, `OLDEST`, and `SHALLOWEST`. Rules are applied in order, with portable path, scan ID, and entry ID as stable final ties. Newest/oldest retain nanosecond timestamp precision. The default rule is SHALLOWEST.
4. Saved manual choices override automatic decisions for the same recorded path and content group. They cannot remove protected, erroneous, conflicting, or out-of-scope paths. All selected observations of that path/content share its effective decision. AUTO removes the override; reset removes all overrides.

Overlapping scans do not establish distinct physical copies. A recorded path can have multiple historical observations. Conflicting content observations at one path cannot provide a valid keeper for removals. Distinct hardlink identities, live existence, and current source contents are not available from this inventory.

## Save, generate, validate

Saving creates a DRAFT definition without generating. Definition edits and manual choices increment its revision and make the previous snapshot stale. Multiple manual choices can accumulate against the last snapshot while the definition is unchanged; regenerate to apply them. Definition changes require regeneration before manual editing. Reloading restores the existing snapshot without automatically starting work.

Generate streams the selected evidence into the state database in one activated appender transaction. It applies overrides, calculates group/path statistics, validates decisions, and publishes the completed generation atomically. A failure, occurrence cap, or timeout preserves the previous snapshot. Invalid decisions can be published as a DRAFT snapshot for review; they never become READY.

Validation requires the current revision's snapshot. Every group containing REMOVE candidates must retain at least one confirmed regular-file candidate without conflicting history or recorded errors. Protected/out-of-scope/error removals, UNDECIDED choices, and overrides absent from the new scope/content block readiness. UNRESOLVED paths produce warnings and stay outside removals. Reset stale overrides after changing scope.

Explicit validation recomputes the selected inventory fingerprint, including normalized scope, scan metadata, coverage, paths, identifiers, sizes, full modified timestamps, hashes, target flags, and recorded errors. Changed evidence makes the snapshot DRAFT and requires regeneration. The scanner lock prevents concurrent CLI writes during each read. READY means the saved plan passes these checks; `planningOnly` and `liveRevalidationRequired` remain true. Later execution would require fresh source checks.

Export performs this validation again for an expected revision and rejects stale or invalid snapshots. Successful server delivery records EXPORTED and the last export ID, format, timestamp, byte count, record count, and SHA-256 in the snapshot. Revalidation preserves EXPORTED when the same snapshot remains valid. Edits/manual choices return to DRAFT; regeneration replaces the snapshot and returns READY or DRAFT. EXPORTED does not mean a client saved the file, live paths were checked, or cleanup occurred.

Statistics count full observations and distinct recorded paths. Byte totals are exact decimal strings. `observedBytes` counts historical observations; `candidateBytes` sums distinct recorded removal paths within content groups. Neither measures reclaimable physical storage. Conflicting historical paths may appear in multiple content groups; state-category counts can overlap for such paths.

## API and limits

Create `POST /api/v1/scenarios` with a body such as:

```json
{
  "name": "Retain the preferred scan",
  "description": "Review recorded copies in two inventories",
  "config": {
    "request": { "scanIds": [1, 2], "mode": "ACROSS_SCANS" },
    "rules": [{ "kind": "PREFER_SCAN", "scanId": 1 }, { "kind": "NEWEST" }],
    "protections": [{ "scanId": 2, "path": "Documents" }],
    "manual": false,
    "maxOccurrences": 1000000,
    "maxSeconds": 120
  }
}
```

PUT sends the same definition plus `revision`. Generate, validate, and reset bodies contain `{ "revision": 1 }`. Overrides send `{ "revision": 1, "decisions": [{ "scanId": 2, "entryId": 42, "decision": "KEEP" }] }`. DELETE requires the revision query parameter. Stale expected revisions return HTTP 409. Group and decision pages use separate cursors bound to the current scenario revision/generation and endpoint; edits invalidate previous cursors. The complete endpoint list is in [WEB_UI.md](WEB_UI.md).

Names are bounded to 120 characters, descriptions to 2,000, configuration JSON to 128 KiB, ordered rules to 32, protections to 100, and each override request to 100 entries. Scenario lists page 1–100 definitions (default 50); generated groups and observations page 1–500 (default 100). Generation defaults to at most 1,000,000 full observations and 120 seconds; configured maxima are 1,000,000 and 600 seconds. Out-of-filter observations count toward the cap.

The time budget bounds native SQL work and row transfers through publication updates; durable transaction completion and startup are not hard wall-clock guarantees. JDBC query timeouts use the remaining budget, and budget checks during streaming prevent partial publication. Scanner and state sessions apply finite DuckDB memory/thread settings. Database work stays on the coordinator; result trees are never accumulated in JVM collections.

State schema v2 adds scenario tables and transactionally migrates v1 saved searches without changing their identities. Unknown versions or unexpected tables are rejected. The separately locked state file must differ from scanner data. Definitions are isolated by canonical scanner database path; moving a database changes that identity.
