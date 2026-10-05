import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnChanges, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { ArchiveAnalysisComponent } from './archive-analysis';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { copy } from './copy';
import { formatBytes, parseBytes } from './file-values';
import { InventoryApi } from './inventory.api';
import { CandidateBadgesComponent, CandidateLegendComponent, applySignatureChange } from './candidate-badges';
import {
  ApiError, DuplicateGroup, DuplicateOccurrencePage, DuplicatePage, DuplicateRequest,
  Entry, NameOperator, Page, Scan
} from './inventory.models';

@Component({
  selector: 'app-duplicate-explorer',
  standalone: true,
  imports: [FormsModule, CandidateBadgesComponent, CandidateLegendComponent, ArchiveAnalysisComponent],
  templateUrl: './duplicate-explorer.html',
  styleUrls: ['./file-search.css', './duplicate-explorer.css']
})
export class DuplicateExplorerComponent implements OnInit, OnChanges, OnDestroy {
  private readonly api = inject(InventoryApi);
  private readonly subscriptions = new Subscription();
  private groupRequest?: Subscription;
  private occurrenceRequest?: Subscription;
  private scanRequest?: Subscription;
  private cursor: string | null = null;
  private history: (string | null)[] = [];
  private occurrenceCursor: string | null = null;
  private occurrenceHistory: (string | null)[] = [];
  private submitted?: DuplicateRequest;
  private scanCursor: string | null = null;
  private appliedScanFilter = '';
  private scanHistory: (string | null)[] = [];
  private readonly scanNames = new Map<number, string>();

  readonly archiveScanIds = signal<number[]>([]);
  readonly initialScanId = input<number | null>(null);
  readonly initialEntryId = input<number | null>(null);
  readonly entryOpen = output<Entry>();
  readonly signatureOpen = output<Entry>();
  readonly directoryOpen = output<{ scanId: number; entryId: number }>();
  readonly scanOpen = output<number>();
  readonly clearReference = output<number>();
  readonly scenarioOpen = output<DuplicateRequest>();
  readonly copy = copy;
  readonly formatBytes = formatBytes;
  readonly scans = signal<Page<Scan> | null>(null);
  readonly results = signal<DuplicatePage | null>(null);
  readonly selectedGroup = signal<DuplicateGroup | null>(null);
  readonly occurrences = signal<DuplicateOccurrencePage | null>(null);
  readonly loading = signal(false);
  readonly occurrenceLoading = signal(false);
  readonly scanLoading = signal(false);
  readonly problem = signal<ApiError | null>(null);
  readonly occurrenceProblem = signal<ApiError | null>(null);
  readonly scanProblem = signal<ApiError | null>(null);
  scanNameFilter = '';
  form = this.defaultForm();

  ngOnChanges(): void {
    this.form = this.defaultForm();
    const id = this.initialScanId();
    if (id) {
      this.form.scanIds = [id];
      this.subscriptions.add(this.api.scan(id).subscribe({
        next: scan => this.scanNames.set(scan.scanId, scan.name),
        error: () => { /* The group request reports a missing reference scan. */ }
      }));
      this.apply();
    } else {
      this.groupRequest?.unsubscribe();
      this.results.set(null); this.problem.set(null); this.loading.set(false);
      this.submitted = undefined; this.archiveScanIds.set([]); this.closeGroup();
    }
  }

  ngOnInit(): void {
    this.loadScans(null);
    this.subscriptions.add(this.api.signatureChanges.subscribe(change => {
      this.results.update(page => page ? { ...page, items: page.items.map(item => applySignatureChange(item, change)) } : null);
      this.selectedGroup.update(group => group ? applySignatureChange(group, change) : null);
      this.occurrences.update(page => page ? { ...page, group: applySignatureChange(page.group, change),
        items: page.items.map(item => applySignatureChange(item, change)) } : null);
    }));
  }
  ngOnDestroy(): void {
    this.groupRequest?.unsubscribe(); this.occurrenceRequest?.unsubscribe();
    this.scanRequest?.unsubscribe(); this.subscriptions.unsubscribe();
  }

  findScans(): void { this.appliedScanFilter = this.scanNameFilter; this.scanHistory = []; this.loadScans(null); }
  nextScans(): void {
    const next = this.scans()?.page.nextCursor;
    if (next) { this.scanHistory.push(this.scanCursor); this.loadScans(next); }
  }
  previousScans(): void {
    if (this.scanHistory.length) this.loadScans(this.scanHistory.pop() ?? null);
  }
  canPreviousScans(): boolean { return this.scanHistory.length > 0; }
  selectScans(event: Event): void {
    const control = event.target as HTMLSelectElement;
    const visible = new Set(this.scans()?.items.map(scan => scan.scanId));
    const retained = this.form.scanIds.filter(id => !visible.has(id));
    const selected = [...retained, ...Array.from(control.selectedOptions).map(option => Number(option.value))];
    if (selected.length > 1000) {
      this.scanProblem.set({ code: 'INVALID_FILTER', message: copy.tooManySelectedScans });
      for (const option of Array.from(control.options)) option.selected = this.form.scanIds.includes(Number(option.value));
      return;
    }
    this.scanProblem.set(null); this.form.scanIds = selected;
  }
  removeScan(id: number): void { this.form.scanIds = this.form.scanIds.filter(value => value !== id); }
  scanLabel(id: number): string { return this.scanNames.get(id) ?? `${copy.scanLabel} ${id}`; }

  apply(event?: Event): void {
    event?.preventDefault();
    let request: DuplicateRequest;
    try { request = this.buildRequest(); }
    catch (error) {
      this.groupRequest?.unsubscribe(); this.loading.set(false);
      this.results.set(null); this.closeGroup(); this.submitted = undefined; this.archiveScanIds.set([]);
      this.problem.set({ code: 'INVALID_FILTER', message: (error as Error).message });
      return;
    }
    this.submitted = request; this.archiveScanIds.set([...request.scanIds]); this.history = []; this.closeGroup();
    this.loadGroups(null);
  }
  next(): void {
    const next = this.results()?.page.nextCursor;
    if (next) { this.history.push(this.cursor); this.loadGroups(next); }
  }
  previous(): void {
    if (this.history.length) this.loadGroups(this.history.pop() ?? null);
  }
  canPrevious(): boolean { return this.history.length > 0; }
  createScenario(): void { if (this.submitted) this.scenarioOpen.emit(structuredClone(this.submitted)); }
  planGroup(): void {
    const first = this.occurrences()?.items[0];
    if (first?.scanId && this.submitted) this.scenarioOpen.emit({ ...structuredClone(this.submitted),
      entry: { scanId: first.scanId, entryId: first.entryId } });
  }

  openGroup(group: DuplicateGroup): void {
    this.selectedGroup.set(group); this.occurrenceHistory = [];
    this.loadOccurrences(null);
  }
  closeGroup(): void {
    this.occurrenceRequest?.unsubscribe(); this.selectedGroup.set(null);
    this.occurrences.set(null); this.occurrenceProblem.set(null); this.occurrenceLoading.set(false);
  }
  nextOccurrences(): void {
    const next = this.occurrences()?.page.nextCursor;
    if (next) { this.occurrenceHistory.push(this.occurrenceCursor); this.loadOccurrences(next); }
  }
  previousOccurrences(): void {
    if (this.occurrenceHistory.length) this.loadOccurrences(this.occurrenceHistory.pop() ?? null);
  }
  canPreviousOccurrences(): boolean { return this.occurrenceHistory.length > 0; }
  openContainingDirectory(entry: Entry): void {
    if (entry.scanId && entry.parentId > 0) this.directoryOpen.emit({ scanId: entry.scanId, entryId: entry.parentId });
  }
  formatModified(entry: Entry): string {
    if (entry.modifiedSec == null || entry.modifiedNano == null) return 'Unknown';
    return new Date(entry.modifiedSec * 1000 + Math.floor(entry.modifiedNano / 1_000_000)).toLocaleString();
  }

  private loadScans(cursor: string | null): void {
    this.scanRequest?.unsubscribe(); this.scanCursor = cursor;
    this.scanLoading.set(true); this.scanProblem.set(null);
    this.scanRequest = this.api.scans(cursor, { name: this.appliedScanFilter, phase: '', hasErrors: '' }, 100).subscribe({
      next: page => {
        page.items.forEach(scan => this.scanNames.set(scan.scanId, scan.name));
        // Keep labels for the current bounded page and selected scans only.
        const retained = new Set([...this.form.scanIds, ...page.items.map(scan => scan.scanId)]);
        for (const id of this.scanNames.keys()) if (!retained.has(id)) this.scanNames.delete(id);
        this.scans.set(page); this.scanLoading.set(false);
      },
      error: failure => { this.scanProblem.set(this.apiError(failure)); this.scanLoading.set(false); }
    });
  }
  private loadGroups(cursor: string | null): void {
    if (!this.submitted) return;
    this.groupRequest?.unsubscribe(); this.closeGroup(); this.cursor = cursor;
    this.loading.set(true); this.problem.set(null); this.results.set(null);
    this.groupRequest = this.api.duplicateGroups({ ...this.submitted, cursor }).subscribe({
      next: page => { this.results.set(page); this.loading.set(false); },
      error: failure => { this.problem.set(this.apiError(failure)); this.loading.set(false); }
    });
  }
  private loadOccurrences(cursor: string | null): void {
    const group = this.selectedGroup();
    if (!group || !this.submitted) return;
    this.occurrenceRequest?.unsubscribe(); this.occurrenceCursor = cursor;
    this.occurrenceLoading.set(true); this.occurrenceProblem.set(null); this.occurrences.set(null);
    this.occurrenceRequest = this.api.duplicateOccurrences(group.groupId, { ...this.submitted, cursor }).subscribe({
      next: page => {
        this.occurrences.set(page); this.occurrenceLoading.set(false);
        window.setTimeout(() => document.getElementById('duplicate-occurrences')?.scrollIntoView({ block: 'start' }), 0);
      },
      error: failure => { this.occurrenceProblem.set(this.apiError(failure)); this.occurrenceLoading.set(false); }
    });
  }
  private buildRequest(): DuplicateRequest {
    const form = this.form;
    if (!form.scanIds.length) throw new Error(copy.selectScansRequired);
    if (form.mode === 'ACROSS_SCANS' && form.scanIds.length < 2) throw new Error(copy.acrossScansRequired);
    if (!Number.isSafeInteger(form.minOccurrences) || form.minOccurrences < 2 ||
        !Number.isSafeInteger(form.minScans) || form.minScans < 1) throw new Error(copy.groupMinimumInvalid);
    const sizeMin = parseBytes(form.sizeMin, copy.minimumSize);
    const sizeMax = parseBytes(form.sizeMax, copy.maximumSize);
    if (sizeMin != null && sizeMax != null && BigInt(sizeMin) > BigInt(sizeMax)) throw new Error(copy.sizeRangeInvalid);
    const after = form.modifiedAfter ? Math.floor(new Date(form.modifiedAfter).getTime() / 1000) : undefined;
    const before = form.modifiedBefore ? Math.floor(new Date(form.modifiedBefore).getTime() / 1000) : undefined;
    if ((after != null && !Number.isFinite(after)) || (before != null && !Number.isFinite(before))) throw new Error(copy.invalidDate);
    if (after != null && before != null && after > before) throw new Error(copy.modifiedRangeInvalid);
    const request: DuplicateRequest = {
      scanIds: [...form.scanIds], limit: form.limit, mode: form.mode,
      minOccurrences: form.minOccurrences, minScans: form.minScans,
      sort: { field: form.sortField, direction: form.sortDirection }, errorState: form.errorState,
      size: { min: sizeMin, max: sizeMax }, modified: { min: after, max: before },
      extensions: form.extensions.split(',').map(value => value.trim()).filter(Boolean),
      path: { contains: form.pathContains || undefined, under: form.pathUnder || undefined,
        excludes: form.pathExcludes.split('\n').filter(Boolean) }
    };
    if (form.nameValue) request.name = { operator: form.nameOperator, value: form.nameValue, caseSensitive: form.caseSensitive };
    const id = this.initialScanId(), entryId = this.initialEntryId();
    if (id && entryId) {
      if (!form.scanIds.includes(id)) throw new Error(copy.referenceScanRequired);
      request.entry = { scanId: id, entryId };
    }
    return request;
  }
  private defaultForm() {
    return {
      scanIds: [] as number[], mode: 'ANY' as 'ANY' | 'ACROSS_SCANS', minOccurrences: 2, minScans: 1,
      nameOperator: 'CONTAINS' as NameOperator, nameValue: '', caseSensitive: false,
      pathContains: '', pathUnder: '', pathExcludes: '', extensions: '', sizeMin: '', sizeMax: '',
      modifiedAfter: '', modifiedBefore: '', errorState: 'ANY' as 'ANY' | 'HAS' | 'NONE',
      sortField: 'SIZE' as 'SIZE' | 'OCCURRENCES' | 'SCANS' | 'OBSERVED_BYTES',
      sortDirection: 'DESC' as 'ASC' | 'DESC', limit: 100
    };
  }
  private apiError(failure: HttpErrorResponse): ApiError {
    const body = failure.error as Partial<ApiError> | null;
    return { code: body?.code ?? 'REQUEST_FAILED',
      message: failure.status === 423 && body?.code === 'DATABASE_LOCKED' ? copy.locked : body?.message ?? copy.failed };
  }
}
