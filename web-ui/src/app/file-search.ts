import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime } from 'rxjs';
import { copy } from './copy';
import { requestFromSearch } from './scenario-form';
import { InventoryApi } from './inventory.api';
import { CandidateBadgesComponent, CandidateLegendComponent, applySignatureChange } from './candidate-badges';
import {
  ApiError, DuplicateRequest, Entry, EntryKind, FileSearchPage, FileSearchRequest, NameOperator,
  SavedSearch, Scan
} from './inventory.models';

interface SearchForm {
  scanIds: number[];
  directoryEnabled: boolean;
  directoryScanId: string;
  directoryEntryId: string;
  recursive: boolean;
  nameOperator: NameOperator;
  nameValue: string;
  caseSensitive: boolean;
  pathContains: string;
  pathStarts: string;
  pathUnder: string;
  pathExcludes: string;
  extensions: string;
  sizeExact: string;
  sizeMin: string;
  sizeMax: string;
  modifiedAfter: string;
  modifiedBefore: string;
  kinds: Record<EntryKind, boolean>;
  hashState: 'ANY' | 'HASHED' | 'UNHASHED';
  duplicate: 'ANY' | 'CONFIRMED' | 'NOT_CONFIRMED' | 'COPIES_2' | 'COPIES_3' | 'ACROSS_SCANS';
  errorState: 'ANY' | 'HAS' | 'NONE';
  sortField: 'PATH' | 'NAME' | 'SIZE' | 'MODIFIED' | 'SCAN';
  sortDirection: 'ASC' | 'DESC';
  limit: number;
}

@Component({
  selector: 'app-file-search',
  standalone: true,
  imports: [FormsModule, CandidateBadgesComponent, CandidateLegendComponent],
  templateUrl: './file-search.html',
  styleUrl: './file-search.css'
})
export class FileSearchComponent implements OnInit, OnDestroy {
  private readonly api = inject(InventoryApi);
  private readonly subscriptions = new Subscription();
  private readonly changes = new Subject<void>();
  private searchRequest?: Subscription;
  private cursor: string | null = null;
  private history: (string | null)[] = [];

  readonly entryOpen = output<Entry>();
  readonly signatureOpen = output<Entry>();
  readonly directoryOpen = output<{ scanId: number; entryId: number }>();
  readonly scanOpen = output<number>();
  readonly scenarioOpen = output<DuplicateRequest>();
  readonly initialScanId = input<number | null>(null);
  readonly initialDirectoryId = input<number | null>(null);
  readonly copy = copy;
  readonly kinds: EntryKind[] = ['FILE', 'DIRECTORY', 'SYMLINK', 'OTHER'];
  readonly scans = signal<Scan[]>([]);
  readonly saved = signal<SavedSearch[]>([]);
  readonly results = signal<FileSearchPage | null>(null);
  readonly loading = signal(false);
  readonly stateBusy = signal(false);
  readonly hasSearched = signal(false);
  readonly problem = signal<ApiError | null>(null);
  readonly savedProblem = signal<ApiError | null>(null);
  readonly selectedSavedId = signal<string | null>(null);
  form: SearchForm = this.defaultForm();
  savedName = '';
  savedDescription = '';

  ngOnInit(): void {
    this.subscriptions.add(this.api.signatureChanges.subscribe(change => {
      this.results.update(page => page ? { ...page, items: page.items.map(item => applySignatureChange(item, change)) } : null);
    }));
    const initialScan = this.initialScanId();
    const initialDirectory = this.initialDirectoryId();
    if (initialScan) this.form.scanIds = [initialScan];
    if (initialScan && initialDirectory) {
      this.form.directoryEnabled = true;
      this.form.directoryScanId = String(initialScan);
      this.form.directoryEntryId = String(initialDirectory);
    }
    this.subscriptions.add(this.api.scans(null, { name: '', phase: '', hasErrors: '' }, 500).subscribe({
      next: page => this.scans.set(page.items),
      error: failure => this.problem.set(this.apiError(failure))
    }));
    this.loadSavedSearches();
    this.subscriptions.add(this.changes.pipe(debounceTime(350)).subscribe(() => {
      if (this.hasSearched()) this.startSearch(null, true);
    }));
  }

  ngOnDestroy(): void {
    this.searchRequest?.unsubscribe();
    this.subscriptions.unsubscribe();
  }

  scheduleSearch(): void { this.changes.next(); }

  apply(event: Event): void {
    event.preventDefault();
    this.startSearch(null, true);
  }

  next(): void {
    const next = this.results()?.page.nextCursor;
    if (!next) return;
    this.history.push(this.cursor);
    this.startSearch(next, false);
  }

  previous(): void {
    if (!this.history.length) return;
    this.startSearch(this.history.pop() ?? null, false);
  }

  canPrevious(): boolean { return this.history.length > 0; }

  selectScans(event: Event): void {
    const select = event.target as HTMLSelectElement;
    this.form.scanIds = Array.from(select.selectedOptions).map(option => Number(option.value));
    this.scheduleSearch();
  }

  toggleKind(kind: EntryKind, checked: boolean): void {
    this.form.kinds[kind] = checked;
    this.scheduleSearch();
  }

  openResult(entry: Entry): void { this.entryOpen.emit(entry); }

  openContainingDirectory(entry: Entry): void {
    if (entry.scanId && entry.parentId > 0) {
      this.directoryOpen.emit({ scanId: entry.scanId, entryId: entry.parentId });
    }
  }

  loadSaved(event: Event): void {
    const id = (event.target as HTMLSelectElement).value;
    if (!id) {
      this.selectedSavedId.set(null);
      this.savedName = '';
      this.savedDescription = '';
      return;
    }
    const saved = this.saved().find(item => item.id === id);
    if (!saved) return;
    this.selectedSavedId.set(saved.id);
    this.savedName = saved.name;
    this.savedDescription = saved.description;
    this.form = this.formFromRequest(saved.request);
    this.startSearch(null, true);
  }

  saveNew(): void { this.persistSavedSearch(null); }

  updateSaved(): void {
    const id = this.selectedSavedId();
    if (id) this.persistSavedSearch(id);
  }

  deleteSaved(): void {
    const id = this.selectedSavedId();
    if (!id || !window.confirm(copy.deleteSavedConfirm)) return;
    this.stateBusy.set(true);
    this.savedProblem.set(null);
    this.subscriptions.add(this.api.deleteSavedSearch(id).subscribe({
      next: () => {
        this.selectedSavedId.set(null);
        this.savedName = '';
        this.savedDescription = '';
        this.stateBusy.set(false);
        this.loadSavedSearches();
      },
      error: failure => { this.savedProblem.set(this.apiError(failure)); this.stateBusy.set(false); }
    }));
  }

  formatBytes(value: string | number): string {
    const bytes = BigInt(value);
    if (bytes < 1024n) return bytes.toString() + ' B';
    const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
    let divisor = 1024n, index = 0;
    while (index < units.length - 1 && bytes >= divisor * 1024n) { divisor *= 1024n; index++; }
    const tenth = (bytes % divisor) * 10n / divisor;
    return (bytes / divisor).toString() + '.' + tenth.toString() + ' ' + units[index];
  }

  formatModified(entry: Entry): string {
    if (entry.modifiedSec == null || entry.modifiedNano == null) return 'Unknown';
    return new Date(entry.modifiedSec * 1000 + Math.floor(entry.modifiedNano / 1_000_000)).toLocaleString();
  }

  duplicateLabel(entry: Entry): string {
    if (!entry.sha256 || entry.duplicateCount == null) return copy.unknownHash;
    return entry.duplicateCount >= 2 ? `${entry.duplicateCount} ${copy.confirmed}` : copy.noConfirmedDuplicate;
  }

  createScenario(): void {
    try { this.scenarioOpen.emit(requestFromSearch(this.buildRequest())); }
    catch (error) { this.problem.set({ code: 'INVALID_SCENARIO', message: (error as Error).message }); }
  }

  private startSearch(cursor: string | null, resetHistory: boolean): void {
    let request: FileSearchRequest;
    try { request = this.buildRequest(); }
    catch (error) {
      this.problem.set({ code: 'INVALID_FILTER', message: (error as Error).message });
      return;
    }
    if (resetHistory) this.history = [];
    this.cursor = cursor;
    if (cursor) request.cursor = cursor;
    this.searchRequest?.unsubscribe();
    this.loading.set(true);
    this.problem.set(null);
    this.hasSearched.set(true);
    this.searchRequest = this.api.searchFiles(request).subscribe({
      next: page => { this.results.set(page); this.loading.set(false); },
      error: failure => { this.problem.set(this.apiError(failure)); this.loading.set(false); }
    });
  }

  private buildRequest(): FileSearchRequest {
    const kinds = this.kinds.filter(kind => this.form.kinds[kind]);
    if (!kinds.length) throw new Error(copy.kindRequired);
    const request: FileSearchRequest = {
      limit: Number(this.form.limit), scanIds: [...this.form.scanIds], kinds,
      hashState: this.form.hashState, duplicate: this.form.duplicate,
      errorState: this.form.errorState,
      sort: { field: this.form.sortField, direction: this.form.sortDirection }
    };
    if (this.form.directoryEnabled) {
      const scanId = this.positiveInteger(this.form.directoryScanId, copy.directoryScanId);
      const entryId = this.positiveInteger(this.form.directoryEntryId, copy.directoryEntryId);
      request.directory = { scanId, entryId, recursive: this.form.recursive };
      request.scanIds = [scanId];
    }
    if (this.form.nameValue) request.name = {
      operator: this.form.nameOperator, value: this.form.nameValue,
      caseSensitive: this.form.caseSensitive
    };
    const excludes = this.form.pathExcludes.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
    if (this.form.pathContains || this.form.pathStarts || this.form.pathUnder || excludes.length) {
      request.path = {};
      if (this.form.pathContains) request.path.contains = this.form.pathContains;
      if (this.form.pathStarts) request.path.startsWith = this.form.pathStarts;
      if (this.form.pathUnder) request.path.under = this.form.pathUnder;
      if (excludes.length) request.path.excludes = excludes;
    }
    const extensions = this.form.extensions.split(/[\s,]+/).map(value => value.trim()).filter(Boolean);
    if (extensions.length) request.extensions = extensions;
    const exact = this.parseBytes(this.form.sizeExact, copy.exactSize);
    const min = this.parseBytes(this.form.sizeMin, copy.minimumSize);
    const max = this.parseBytes(this.form.sizeMax, copy.maximumSize);
    if (exact && (min || max)) throw new Error(copy.exactSizeConflict);
    if (min && max && BigInt(min) > BigInt(max)) throw new Error(copy.sizeRangeInvalid);
    if (exact || min || max) request.size = {
      ...(exact ? { exact } : {}), ...(min ? { min } : {}), ...(max ? { max } : {})
    };
    const after = this.epochSeconds(this.form.modifiedAfter, copy.modifiedAfter);
    const before = this.epochSeconds(this.form.modifiedBefore, copy.modifiedBefore);
    if (after != null && before != null && after > before) throw new Error(copy.modifiedRangeInvalid);
    if (after != null || before != null) request.modified = {
      ...(after != null ? { min: after } : {}), ...(before != null ? { max: before } : {})
    };
    return request;
  }

  private persistSavedSearch(id: string | null): void {
    let request: FileSearchRequest;
    try {
      request = this.buildRequest();
      if (!this.savedName.trim()) throw new Error(copy.savedNameRequired);
    } catch (error) {
      this.savedProblem.set({ code: 'INVALID_SAVED_SEARCH', message: (error as Error).message });
      return;
    }
    const body = { name: this.savedName.trim(), description: this.savedDescription, request };
    const operation = id ? this.api.updateSavedSearch(id, body) : this.api.createSavedSearch(body);
    this.stateBusy.set(true);
    this.savedProblem.set(null);
    this.subscriptions.add(operation.subscribe({
      next: saved => {
        this.selectedSavedId.set(saved.id);
        this.savedName = saved.name;
        this.savedDescription = saved.description;
        this.stateBusy.set(false);
        this.loadSavedSearches();
      },
      error: failure => { this.savedProblem.set(this.apiError(failure)); this.stateBusy.set(false); }
    }));
  }

  private loadSavedSearches(): void {
    this.subscriptions.add(this.api.savedSearches().subscribe({
      next: page => this.saved.set(page.items),
      error: failure => this.savedProblem.set(this.apiError(failure))
    }));
  }

  private defaultForm(): SearchForm {
    return {
      scanIds: [], directoryEnabled: false, directoryScanId: '', directoryEntryId: '', recursive: true,
      nameOperator: 'CONTAINS', nameValue: '', caseSensitive: false,
      pathContains: '', pathStarts: '', pathUnder: '', pathExcludes: '', extensions: '',
      sizeExact: '', sizeMin: '', sizeMax: '', modifiedAfter: '', modifiedBefore: '',
      kinds: { FILE: true, DIRECTORY: false, SYMLINK: false, OTHER: false },
      hashState: 'ANY', duplicate: 'ANY', errorState: 'ANY',
      sortField: 'PATH', sortDirection: 'ASC', limit: 100
    };
  }

  private formFromRequest(request: FileSearchRequest): SearchForm {
    const form = this.defaultForm();
    form.scanIds = [...(request.scanIds ?? [])];
    if (request.directory) {
      form.directoryEnabled = true;
      form.directoryScanId = String(request.directory.scanId);
      form.directoryEntryId = String(request.directory.entryId);
      form.recursive = request.directory.recursive;
    }
    if (request.name) {
      form.nameOperator = request.name.operator;
      form.nameValue = request.name.value;
      form.caseSensitive = request.name.caseSensitive;
    }
    form.pathContains = request.path?.contains ?? '';
    form.pathStarts = request.path?.startsWith ?? '';
    form.pathUnder = request.path?.under ?? '';
    form.pathExcludes = request.path?.excludes?.join('\n') ?? '';
    form.extensions = request.extensions?.join(', ') ?? '';
    form.sizeExact = request.size?.exact == null ? '' : String(request.size.exact);
    form.sizeMin = request.size?.min == null ? '' : String(request.size.min);
    form.sizeMax = request.size?.max == null ? '' : String(request.size.max);
    form.modifiedAfter = this.localDateTime(request.modified?.min);
    form.modifiedBefore = this.localDateTime(request.modified?.max);
    this.kinds.forEach(kind => form.kinds[kind] = request.kinds?.includes(kind) ?? kind === 'FILE');
    form.hashState = request.hashState ?? 'ANY';
    form.duplicate = request.duplicate ?? 'ANY';
    form.errorState = request.errorState ?? 'ANY';
    form.sortField = request.sort?.field ?? 'PATH';
    form.sortDirection = request.sort?.direction ?? 'ASC';
    form.limit = request.limit ?? 100;
    return form;
  }

  private parseBytes(value: string, label: string): string | undefined {
    const input = value.trim();
    if (!input) return undefined;
    const match = /^(\d+)(?:\.(\d{1,9}))?\s*(B|KB|MB|GB|TB|PB|KIB|MIB|GIB|TIB|PIB)?$/i.exec(input);
    if (!match) throw new Error(`${label}: ${copy.sizeFormat}`);
    const decimalUnits: Record<string, bigint> = {
      B: 1n, KB: 1000n, MB: 1000n ** 2n, GB: 1000n ** 3n, TB: 1000n ** 4n, PB: 1000n ** 5n,
      KIB: 1024n, MIB: 1024n ** 2n, GIB: 1024n ** 3n, TIB: 1024n ** 4n, PIB: 1024n ** 5n
    };
    const multiplier = decimalUnits[(match[3] ?? 'B').toUpperCase()];
    const whole = BigInt(match[1]) * multiplier;
    const fractionText = match[2] ?? '';
    const scale = fractionText ? 10n ** BigInt(fractionText.length) : 1n;
    const fraction = fractionText ? (BigInt(fractionText) * multiplier + scale / 2n) / scale : 0n;
    const bytes = whole + fraction;
    if (bytes > 9223372036854775807n) throw new Error(`${label}: ${copy.sizeTooLarge}`);
    return bytes.toString();
  }

  private positiveInteger(value: string, label: string): number {
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`${label}: ${copy.positiveInteger}`);
    return parsed;
  }

  private epochSeconds(value: string, label: string): number | undefined {
    if (!value) return undefined;
    const milliseconds = new Date(value).getTime();
    if (!Number.isFinite(milliseconds)) throw new Error(`${label}: ${copy.invalidDate}`);
    return Math.floor(milliseconds / 1000);
  }

  private localDateTime(seconds?: number): string {
    if (seconds == null) return '';
    const date = new Date(seconds * 1000);
    const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
    return shifted.toISOString().slice(0, 16);
  }

  private apiError(failure: HttpErrorResponse): ApiError {
    const body = failure.error as Partial<ApiError> | null;
    return {
      code: body?.code ?? 'REQUEST_FAILED',
      message: failure.status === 423 && body?.code === 'DATABASE_LOCKED' ?
        copy.locked : body?.message ?? copy.failed
    };
  }
}
