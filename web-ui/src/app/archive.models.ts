import { ArchiveLocation, CandidateSignals, Entry, Page } from './inventory.models';

export interface ArchiveSummary {
  available: boolean;
  filesystem: { candidateFiles: string; candidateBytes: string };
  archive: { candidateMembers: string; candidateLogicalBytes: string; groups: string; directCleanupBytes: string };
  coverage: { members: string; files: string; hashedMembers: string; unresolvedMembers: string;
    rootArchives: string; partialRoots: string; skippedRoots: string; unfinishedRoots: string };
  semantics: string;
}
export interface ArchiveContext {
  scanId: number; scanName: string; rootEntryId: number; parentId: number;
  resultId: string | null; rootResultId: string | null; chain: string; filename: string;
  physicalPath: string; status: string; diagnostic: string | null; reused: boolean; browsable: boolean;
  containers: { chain: string; name: string; status: string }[];
}
export interface ArchiveEntry extends Entry {
  key: string; ordinal?: number; actualSize?: string | null; declaredSize?: string | null;
  integrity?: string; encrypted?: boolean; diagnostic?: string | null; recoveredSha256?: string | null;
  nestedArchive?: { ordinal: number; status: string; reused: boolean; browsable: boolean } | null;
  archiveContext?: ArchiveContext;
}
export interface ArchivePage extends Page<ArchiveEntry> { archive: ArchiveContext; path: string; search: string; }
export interface ArchiveGroup extends CandidateSignals {
  groupId: string; size: string; sha256: string; occurrences: number;
  filesystemOccurrences: number; archiveOccurrences: number; filesystemObservedBytes: string; archiveLogicalBytes: string;
}
export interface ArchiveGroupPage extends Page<ArchiveGroup> { summary: ArchiveSummary; scanIds: number[]; }
export interface ArchiveOccurrence extends Entry {
  rootEntryId: number | null; chain: string; ordinal: number | null;
  locationId: number; memberId: number;
}
export interface ArchiveRoute extends ArchiveLocation { path: string; }

/** All source names are encoded as URL data, never routes or host filesystem paths. */
export function archiveHref(location: ArchiveLocation, path = '', ordinal?: number): string {
  const params = new URLSearchParams();
  if (location.chain) params.set('chain', location.chain);
  if (path) params.set('path', path);
  if (ordinal != null) params.set('member', String(ordinal));
  return '#/scans/' + location.scanId + '/archive/' + location.rootEntryId + (params.size ? '?' + params : '');
}
export function occurrenceHref(item: ArchiveOccurrence): string {
  return item.storageKind === 'ARCHIVE_MEMBER' ? archiveHref({ scanId: item.scanId!, rootEntryId: item.rootEntryId!,
    chain: item.chain }, '', item.ordinal!) : '#/scans/' + item.scanId + '/explore/' + item.parentId;
}
