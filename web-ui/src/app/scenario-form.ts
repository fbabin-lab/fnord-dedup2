import { copy } from './copy';
import { parseBytes } from './file-values';
import { DuplicateRequest, FileSearchRequest, ScenarioConfig } from './inventory.models';

export function requestFromSearch(request: FileSearchRequest): DuplicateRequest {
  const scanIds = request.directory ? [request.directory.scanId] : request.scanIds;
  if (!scanIds?.length) throw new Error('Select scans explicitly before creating a scenario.');
  if (request.hashState === 'UNHASHED' || request.duplicate === 'NOT_CONFIRMED' ||
      (request.kinds?.length && !request.kinds.includes('FILE')))
    throw new Error('Scenario planning requires confirmed regular-file duplicates. Change the search scope first.');
  const across = request.duplicate === 'ACROSS_SCANS';
  return { scanIds: [...scanIds], directory: request.directory, name: request.name, path: request.path,
    extensions: request.extensions, size: request.size, modified: request.modified, errorState: request.errorState,
    mode: across ? 'ACROSS_SCANS' : 'ANY', minOccurrences: request.duplicate === 'COPIES_3' ? 3 : 2,
    minScans: across ? 2 : 1 };
}

function localDate(value: number | undefined): string {
  if (value == null) return '';
  const date = new Date(value * 1000);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
}

export function scenarioForm(request: DuplicateRequest = { scanIds: [] }) {
  const range = request.modified as { exact?: number; min?: number; max?: number } | undefined;
  return {
    name: '', description: '', scanIds: [...request.scanIds], mode: request.mode ?? 'ANY',
    minOccurrences: request.minOccurrences ?? 2, minScans: request.minScans ?? 1,
    nameOperator: request.name?.operator ?? 'CONTAINS', nameValue: request.name?.value ?? '',
    caseSensitive: request.name?.caseSensitive ?? false,
    pathContains: request.path?.contains ?? '', pathStartsWith: request.path?.startsWith ?? '',
    pathUnder: request.path?.under ?? '', pathExcludes: request.path?.excludes?.join('\n') ?? '',
    extensions: request.extensions?.join(', ') ?? '', errorState: request.errorState ?? 'ANY',
    sizeExact: request.size?.exact?.toString() ?? '', sizeMin: request.size?.min?.toString() ?? '',
    sizeMax: request.size?.max?.toString() ?? '', modifiedAfter: localDate(range?.exact ?? range?.min),
    modifiedBefore: localDate(range?.exact ?? range?.max), manual: false, maxOccurrences: 1000000, maxSeconds: 120
  };
}
export type ScenarioForm = ReturnType<typeof scenarioForm>;

export function scopeFromForm(form: ScenarioForm, original: DuplicateRequest): DuplicateRequest {
  if (!form.scanIds.length) throw new Error(copy.selectScansRequired);
  if (form.mode === 'ACROSS_SCANS' && form.scanIds.length < 2) throw new Error(copy.acrossScansRequired);
  if (!Number.isSafeInteger(form.minOccurrences) || form.minOccurrences < 2 ||
      !Number.isSafeInteger(form.minScans) || form.minScans < 1 || form.minScans > 1000)
    throw new Error(copy.groupMinimumInvalid);
  const exact = parseBytes(form.sizeExact, 'Exact size');
  const min = parseBytes(form.sizeMin, copy.minimumSize), max = parseBytes(form.sizeMax, copy.maximumSize);
  if (exact != null && (min != null || max != null)) throw new Error('Exact size cannot be combined with a size range.');
  if (min != null && max != null && BigInt(min) > BigInt(max)) throw new Error(copy.sizeRangeInvalid);
  const after = form.modifiedAfter ? Math.floor(new Date(form.modifiedAfter).getTime() / 1000) : undefined;
  const before = form.modifiedBefore ? Math.floor(new Date(form.modifiedBefore).getTime() / 1000) : undefined;
  if ((after != null && !Number.isFinite(after)) || (before != null && !Number.isFinite(before))) throw new Error(copy.invalidDate);
  if (after != null && before != null && after > before) throw new Error(copy.modifiedRangeInvalid);
  const request: DuplicateRequest = {
    scanIds: [...form.scanIds], mode: form.mode, minOccurrences: form.minOccurrences, minScans: form.minScans,
    path: { contains: form.pathContains || undefined, startsWith: form.pathStartsWith || undefined,
      under: form.pathUnder || undefined, excludes: form.pathExcludes.split('\n').filter(Boolean) },
    name: form.nameValue ? { operator: form.nameOperator, value: form.nameValue, caseSensitive: form.caseSensitive } : undefined,
    extensions: form.extensions.split(',').map(x => x.trim()).filter(Boolean), errorState: form.errorState,
    size: { exact, min, max }, modified: { min: after, max: before },
    directory: original.directory, entry: original.entry
  };
  if ((request.directory && !request.scanIds.includes(request.directory.scanId)) ||
      (request.entry && !request.scanIds.includes(request.entry.scanId)))
    throw new Error('Keep the reference scan selected or clear the directory/file scope.');
  return request;
}

export function applyConfig(form: ScenarioForm, config: ScenarioConfig): ScenarioForm {
  return { ...form, manual: config.manual, maxOccurrences: config.maxOccurrences ?? 1000000, maxSeconds: config.maxSeconds ?? 120 };
}
