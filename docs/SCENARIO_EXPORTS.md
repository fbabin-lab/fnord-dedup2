# Scenario cleanup manifests — version 1

A manifest is a reviewable historical plan. It never executes decisions, opens source files, recalculates hashes, or changes scanner data. Only confirmed regular filesystem evidence participates; archive members and image guest files are excluded. Every item has `requires_revalidation: true`. A future executor would have to validate both the target and retained copies against live type, path identity, size, full timestamp, and SHA-256 before any operation. Historical distinct recorded paths do not prove distinct physical copies or reclaimable space.

## Download

In Scenario Builder, save and generate the plan, resolve validation errors, choose JSONL, JSON, or CSV, then select **Export manifest**. Unsaved, stale, ungenerated, or invalid plans cannot be exported. Export rechecks the source inventory fingerprint, so a previously READY plan can fail and return to DRAFT after a CLI change. Reload or regenerate before retrying.

The API is `POST /api/v1/scenarios/{id}/export`:

```json
{"revision": 1, "format": "JSONL", "maxBytes": 67108864}
```

`revision` is required. `format` is exactly `JSON`, `JSONL`, or `CSV`. `maxBytes` is optional, defaults to 67,108,864 bytes (64 MiB), and accepts 1 through 268,435,456 bytes (256 MiB). The UI uses the default and receives a bounded Blob before initiating the browser download. API clients may stream the response directly to their chosen destination. The server accepts no destination path and produces no shell commands or plain path list.

Successful responses have Content-Disposition attachment with `scenario-{uuid}-r{revision}.{format}`, Content-Length, Cache-Control `no-store`, X-Content-Type-Options `nosniff`, X-Scenario-Revision, X-Export-Id, and X-Manifest-SHA256. Media types are application/json, application/x-ndjson, and text/csv; encoding is UTF-8. Verify the whole-file SHA-256 and completion record when consuming a download. The digest provides integrity, not authentication.

Validation/input/limit failures occur before attachment headers and return the normal JSON API error. Common codes are INVALID_SCENARIO (400), SCENARIO_NOT_FOUND (404), SCENARIO_NOT_GENERATED, SCENARIO_REVISION_CONFLICT, STALE_SCENARIO, SCENARIO_NOT_READY, SCENARIO_EXPORT_LIMIT_EXCEEDED, SCENARIO_TIMEOUT (409), DATABASE_LOCKED (423), and SCENARIO_EXPORT_FAILED (503 for temporary-storage failures). Source-change validation is saved as DRAFT with SOURCE_CHANGED even though export is refused.

## Common data contract

Metadata contains `manifestVersion: 1`, export ID/time/format, scenario ID/revision/name/description, generation ID/time and validation time, read-only source database registration and selected-evidence fingerprint, explicit source scan metadata, normalized configuration with filters/rules/protections, summary/coverage/validation, algorithm version, limits, and historical/live-revalidation flags.

Items contain every saved KEEP, REMOVE, or UNRESOLVED decision in the generated content groups. Export rejects UNDECIDED choices. An item represents one **recorded path per content group**; overlapping selected scans at that path/content are coalesced. `observationCount` includes those aliases. The representative observation has the lowest scan ID, then entry ID. Other alias rows remain available through scenario decision pages. Conflicting historical content at one path can produce separate unresolved items in different groups; none may become a removal candidate.

| Item field | Meaning |
|---|---|
| `type` | `decision`. |
| `itemId` | SHA-256 of content group ID, NUL separator, and the portable path identity; stable for this content/path. |
| `groupId` | Recorded byte size, colon, completed SHA-256. |
| `decision`, `reason` | Saved effective choice and reason code, including manual choices and protections. |
| `observationCount` | Selected observations coalesced into this item. |
| `reference` | Scan/entry/parent IDs, scan name/root, relative path, filename, full portable path, and `kind: FILE`. |
| `expected` | Byte size, modification epoch seconds/nanoseconds, `algorithm: SHA-256`, and completed digest. |
| `matchesFilters`, `protected`, `hasError`, `conflicting` | Snapshot flags explaining eligibility and unresolved evidence. |
| `keepReference` | For REMOVE only, one confirmed retained observation with `reference` and `expected`; null for other choices. |
| `requires_revalidation` | Always true. |

A removal's retained reference uses the first eligible KEEP observation ordered by portable path identity, scan ID, then entry ID. It has the same size/SHA-256, a different recorded path identity, no conflicting history, and no recorded error. Including one reference bounds the manifest when a group has many keepers; all other saved KEEP paths also appear as items. Protections and paths outside target filters remain visible as KEEP items.

Exported identifiers, scenario revision, sizes, epoch seconds, counts, and wide byte totals use decimal strings where precision can exceed JavaScript's integer range. Nanosecond adjustments and bounded version/limit fields use JSON numbers. Paths remain portable strings, including Windows drive/UNC roots, UTF-8, literal POSIX backslashes, quotes, commas, and newlines. No host path-provider interpretation is needed for offline/foreign roots.

The completion record has `type: completion`, matching export ID, `complete: true`, record count, observation count, removal-candidate count, exact candidate bytes, and `recordsSha256`. Record count includes KEEP and UNRESOLVED items; it can differ from the count of globally distinct paths when conflicting content exists. The record digest covers the exact UTF-8 decision JSON records in their JSONL form and order, each followed by LF. It excludes metadata and completion and is identical across formats for unchanged decisions. It does not define a general JSON canonicalization scheme; use the JSONL record bytes to reproduce it. The whole-file digest differs by format and export metadata/time.

## Format layouts

**JSON** is one object with `metadata`, `items` (array streamed row by row), and `completion`. No whole array is built in server memory.

**JSONL** has a manifest metadata record first (`type: manifest`), then one decision JSON object per line, then the completion record. Embedded filename newlines are JSON escapes, never record separators. The completion record is mandatory; a truncated sequence must be rejected. JSON and JSONL are the authoritative machine-readable formats.

**CSV** has a fixed header, a manifest row, decision rows, and a completion row. `manifest_json` contains the metadata/completion JSON for the corresponding rows and is empty on decision rows. All cells are RFC-style double-quoted with doubled quote escaping and CRLF record separators. No BOM is added. Columns are:

```text
record_type,manifest_json,item_id,group_id,decision,reason,observation_count,
scan_id,entry_id,parent_id,scan_name_json,scan_root_json,relative_path_json,
filename_json,path_json,kind,expected_size,modified_sec,modified_nano,sha256,
matches_filters,protected,has_error,conflicting,keep_reference_json,requires_revalidation
```

Each `_json` text field must first be CSV-decoded, then JSON-decoded. Name/path columns encode a JSON string, so arbitrary leading spreadsheet formula characters cannot become formula cells and unusual names round-trip without prefixing or altering the stored path. `keep_reference_json` encodes an object or is empty. Metadata marks `csvTextEncoding: JSON_STRING`. Spreadsheet software can round large numeric-looking cells; use authoritative JSON/JSONL for automation and exact values.

## Status, resources, and lifecycle

The server stages one complete export in a private, randomly named system temporary file before responding. SQL and serialization stream one item at a time. The byte cap includes metadata, all UTF-8 decision data, separators, and completion. Occurrence/time limits from the saved scenario also apply: at most 1,000,000 full observations and 600 seconds, with the usual 120-second default. The time budget covers revalidation and staging, including native SQL timeouts; startup, transaction durability, and HTTP delivery are not hard wall-clock guarantees. DuckDB queries retain finite memory/thread settings and may spill.

The state owner serializes preparation and delivery/status publication; a slow download can delay other state requests. The scanner lock is held only during selected-evidence revalidation. A subsequent CLI change does not rewrite a staged manifest; it identifies the historical fingerprint/time that was checked. No live filesystem snapshot is promised.

After successful server delivery, the matching revision/generation becomes EXPORTED and `snapshot.lastExport` stores export ID, format, export timestamp, revision/generation, bytes, record count, and whole-file SHA-256. Exports do not increment the scenario revision. Revalidation of that unchanged valid snapshot preserves EXPORTED. Edits/manual choices make it DRAFT; regeneration replaces export metadata. A completion for a superseded or deleted generation cannot mark the current scenario EXPORTED.

EXPORTED records server delivery; it cannot prove a client durably saved the file. A connection/delivery failure does not record a new successful export. Normal success, staging failure, byte/time cap, or delivery error removes the temporary file. Abrupt process termination can leave an owned staging file in the system temporary directory; there is no cross-process scavenger that might delete another active export. Files are capped per request, not by an aggregate whole-server temporary-disk quota. Export artifacts are not retained in the state database; download again when needed.

State schema remains v2; last-export metadata is stored in the existing versioned snapshot JSON. No scanner schema or tables change. Tests cover real scanner databases, every format, source/revision/keeper guards, aliases, unusual names, exact values, staging/delivery cleanup, EXPORTED persistence, and unchanged scanner bytes. Packaged HTTP and browser tests also verify headers, checksums, actual downloads, reload, and desktop/mobile layout.
