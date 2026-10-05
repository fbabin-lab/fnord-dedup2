export interface PageInfo { limit: number; nextCursor: string | null; hasMore: boolean; }
export interface Page<T> { items: T[]; page: PageInfo; }
export interface Registration { path: string | null; configured: boolean; readOnly: boolean; }
export interface DatabaseStatus {
  path: string;
  baseSchema: number;
  archiveSchema: number | null;
  imageSchema: number | null;
  supported: boolean;
  scanCount: number;
  lastModified: string;
  readOnly: boolean;
}
export interface Dashboard {
  scans: number;
  inventory: { entries: number; files: number; directories: number; fileBytes: string };
  hashesCompleted: number;
  confirmedDuplicateGroups: number;
  scanErrors: number;
  archiveRuns: number | null;
  archiveErrors: number | null;
  imageRuns: number | null;
  imageErrors: number | null;
}
export interface Scan {
  scanId: number;
  name: string;
  root: string;
  phase: string;
  algorithm: string;
  createdAt: string;
  updatedAt: string;
  files: number;
  directories: number;
  symlinks: number;
  otherEntries: number;
  fileBytes: string;
  hashesCompleted: number;
  errors: number;
  pendingDirectories?: number;
  confirmedDuplicateGroups?: number;
}
export interface ScanFilters { name: string; phase: string; hasErrors: string; }
export interface ScanError {
  phase: string;
  relativePath: string;
  message: string;
  recordedAtMs: number;
}
export interface Signature {
  id: string; algorithm: 'SHA-256'; size: string; sha256: string; tag: string; memo: string;
  createdAt: string; updatedAt: string;
}
export interface CandidateSignals {
  signature?: Signature | null;
  signatureMatch?: boolean;
  duplicateCandidate?: boolean;
  removalCandidate?: boolean;
}
export interface SignatureChange { signature: Signature; removed: boolean; }
export interface SignatureMatches extends Page<Entry> {
  signature: Signature; total: number; persistedHashesOnly: boolean;
}
export interface ArchiveLocation { scanId: number; rootEntryId: number; chain: string; ordinal?: number; }
export interface Entry extends CandidateSignals {
  storageKind?: 'FILESYSTEM' | 'ARCHIVE_MEMBER';
  archive?: { rootEntryId: number; status: string; resultId: string | null; reused: boolean; browsable: boolean } | null;
  archiveMember?: ArchiveLocation;
  filesystemOccurrenceCount?: number;
  archiveOccurrenceCount?: number;
  directCleanupEligible?: boolean;
  algorithm?: string;
  scanId?: number;
  scanName?: string;
  scanRoot?: string;
  entryId: number;
  parentId: number;
  relativePath: string;
  path: string;
  filename: string;
  kind: string;
  size: string;
  modifiedSec: number | null;
  modifiedNano: number | null;
  sha256: string | null;
  hashState: string;
  duplicateCount?: number | null;
  duplicateScanCount?: number | null;
  hasError?: boolean;
  relatedErrors?: { phase: string; message: string; recordedAtMs: number }[];
}
export interface ChildPage extends Page<Entry> { directory: Entry; }
export interface Breadcrumb { entryId: number; parentId: number; name: string; path: string; kind: string; }
export interface ApiError { code: string; message: string; }

export type NameOperator = 'CONTAINS' | 'STARTS_WITH' | 'ENDS_WITH' | 'EXACT' | 'GLOB' | 'REGEX';
export type EntryKind = 'FILE' | 'DIRECTORY' | 'SYMLINK' | 'OTHER';
export interface FileSearchRequest {
  limit?: number;
  cursor?: string | null;
  scanIds?: number[];
  directory?: { scanId: number; entryId: number; recursive: boolean };
  name?: { operator: NameOperator; value: string; caseSensitive: boolean };
  path?: { contains?: string; startsWith?: string; under?: string; excludes?: string[] };
  extensions?: string[];
  size?: { exact?: string | number; min?: string | number; max?: string | number };
  modified?: { min?: number; max?: number };
  kinds?: EntryKind[];
  hashState?: 'ANY' | 'HASHED' | 'UNHASHED';
  duplicate?: 'ANY' | 'CONFIRMED' | 'NOT_CONFIRMED' | 'COPIES_2' | 'COPIES_3' | 'ACROSS_SCANS';
  errorState?: 'ANY' | 'HAS' | 'NONE';
  sort?: { field: 'PATH' | 'NAME' | 'SIZE' | 'MODIFIED' | 'SCAN'; direction: 'ASC' | 'DESC' };
}
export interface FileSearchPage extends Page<Entry> {
  coverage: { persistedHashesOnly: boolean; hasUnhashedFiles: boolean };
}
export interface SavedSearch {
  id: string;
  name: string;
  description: string;
  request: FileSearchRequest;
  createdAt: string;
  updatedAt: string;
}
export interface SavedSearchList { items: SavedSearch[]; limit: number; truncated: boolean; }

export interface DuplicateRequest {
  scanIds: number[];
  directory?: FileSearchRequest['directory'];
  limit?: number;
  cursor?: string | null;
  mode?: 'ANY' | 'ACROSS_SCANS';
  minOccurrences?: number;
  minScans?: number;
  entry?: { scanId: number; entryId: number };
  name?: FileSearchRequest['name'];
  path?: FileSearchRequest['path'];
  extensions?: string[];
  size?: FileSearchRequest['size'];
  modified?: FileSearchRequest['modified'];
  errorState?: FileSearchRequest['errorState'];
  sort?: { field: 'SIZE' | 'OCCURRENCES' | 'SCANS' | 'OBSERVED_BYTES'; direction: 'ASC' | 'DESC' };
}

export type ScenarioChoice = 'KEEP' | 'REMOVE' | 'UNDECIDED' | 'UNRESOLVED';
export interface RetentionRule {
  kind: 'PREFER_SCAN' | 'PREFER_PATH' | 'NEWEST' | 'OLDEST' | 'SHALLOWEST';
  scanId?: number;
  path?: string;
}
export interface ProtectedPath { scanId?: number; path: string; }
export interface ScenarioConfig {
  request: DuplicateRequest;
  rules: RetentionRule[];
  protections: ProtectedPath[];
  manual: boolean;
  maxOccurrences?: number;
  maxSeconds?: number;
}
export interface ScenarioSummary {
  groups: number; observations: string; recordedPaths: string; keep: string; remove: string;
  undecided: string; unresolved: string; observedBytes: string; candidateBytes: string;
}
export interface ScenarioIssue { code: string; message: string; count?: number; }
export interface ScenarioSnapshot {
  generationId: string; algorithmVersion: string; sourceFingerprint: string; configFingerprint: string;
  sourceChanged: boolean; planningOnly: boolean; liveRevalidationRequired: boolean;
  coverage: DuplicatePage['coverage']; summary: ScenarioSummary;
  sourceScans: { scanId: number; name: string; root: string; phase: string }[];
  validation: { valid: boolean; errors: ScenarioIssue[]; warnings: ScenarioIssue[] };
}
export interface ScenarioListItem {
  id: string; name: string; description: string; revision: number; status: 'DRAFT' | 'READY';
  createdAt: string; updatedAt: string;
}
export interface Scenario extends ScenarioListItem {
  config: ScenarioConfig; sourceDatabase: string; generationId: string | null;
  generatedRevision: number | null; generatedAt: string | null; snapshot: ScenarioSnapshot | null;
  stale: boolean; definitionStale: boolean;
}
export interface ScenarioGroup {
  groupId: string; size: string; sha256: string; samplePath: string; occurrences: number;
  recordedPaths: number; keep: number; retainedCandidates: number; remove: number;
  undecided: number; unresolved: number; candidateBytes: string;
}
export interface ScenarioDecision extends Entry {
  scanId: number; protected: boolean; conflicting: boolean; matchesFilters: boolean;
  decision: ScenarioChoice; reason: string; autoDecision: ScenarioChoice;
  manualDecision: 'KEEP' | 'REMOVE' | 'UNDECIDED' | null;
}
export interface ScenarioPage<T> extends Page<T> { revision: number; generationId: string; stale: boolean; }
export interface DuplicateGroup extends CandidateSignals {
  groupId: string;
  size: string;
  sha256: string;
  occurrences: number;
  scanCount: number;
  matchingOccurrences: number;
  errorOccurrences: number;
  samplePath: string;
  observedBytes: string;
}
export interface DuplicatePage extends Page<DuplicateGroup> {
  summary: { groups: number; occurrences: string; observedBytes: string };
  coverage: {
    files: number; hashedFiles: number; unhashedFiles: number; selectedScans: number;
    incompleteScans: number; scanErrors: number; persistedHashesOnly: boolean; observationsOnly: boolean;
  };
  reference: { scanId: number; entryId: number; filename: string; relativePath: string; sha256: string } | null;
}
export interface DuplicateOccurrence extends Entry { matchesFilters: boolean; }
export interface DuplicateOccurrencePage extends Page<DuplicateOccurrence> { group: DuplicateGroup; }
