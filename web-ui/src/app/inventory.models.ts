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
export interface Entry {
  entryId: number;
  parentId: number;
  relativePath: string;
  path: string;
  filename: string;
  kind: string;
  size: string;
  modifiedSec: number;
  modifiedNano: number;
  sha256: string | null;
  hashState: string;
  duplicateCount?: number | null;
  relatedErrors?: { phase: string; message: string; recordedAtMs: number }[];
}
export interface ChildPage extends Page<Entry> { directory: Entry; }
export interface Breadcrumb { entryId: number; parentId: number; name: string; path: string; kind: string; }
export interface ApiError { code: string; message: string; }
