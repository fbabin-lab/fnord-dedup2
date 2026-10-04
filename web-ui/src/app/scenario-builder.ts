import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnChanges, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription } from 'rxjs';
import { copy } from './copy';
import { scenarioCopy } from './scenario-copy';
import { applyConfig, requestFromSearch, scenarioForm, scopeFromForm } from './scenario-form';
import { formatBytes } from './file-values';
import { InventoryApi } from './inventory.api';
import {
  ApiError, DuplicateRequest, Entry, Page, ProtectedPath, RetentionRule, SavedSearch,
  Scan, Scenario, ScenarioConfig, ScenarioDecision, ScenarioGroup, ScenarioListItem, ScenarioPage
} from './inventory.models';

@Component({
  selector: 'app-scenario-builder', standalone: true, imports: [FormsModule],
  templateUrl: './scenario-builder.html', styleUrls: ['./file-search.css', './scenario-builder.css']
})
export class ScenarioBuilderComponent implements OnInit, OnChanges, OnDestroy {
  private readonly api = inject(InventoryApi);
  private readonly subscriptions = new Subscription();
  private recordRequest?: Subscription;
  private listRequest?: Subscription;
  private scanRequest?: Subscription;
  private groupRequest?: Subscription;
  private decisionRequest?: Subscription;
  private labelRequests = new Subscription();
  private listCursor: string | null = null;
  private listHistory: (string | null)[] = [];
  private scanCursor: string | null = null;
  private scanHistory: (string | null)[] = [];
  private groupCursor: string | null = null;
  private groupHistory: (string | null)[] = [];
  private decisionCursor: string | null = null;
  private decisionHistory: (string | null)[] = [];
  private scanFilter = '';
  private readonly scanNames = new Map<number, string>();
  private scope: DuplicateRequest = { scanIds: [] };
  private downloadEpoch = 0;
  readonly initialId = input<string | null>(null);
  readonly seed = input<DuplicateRequest | null>(null);
  readonly scenarioOpen = output<string | null>();
  readonly entryOpen = output<Entry>();
  readonly directoryOpen = output<{ scanId: number; entryId: number }>();
  readonly copy = { ...copy, ...scenarioCopy };
  readonly formatBytes = formatBytes;
  readonly record = signal<Scenario | null>(null);
  readonly list = signal<Page<ScenarioListItem> | null>(null);
  readonly scans = signal<Page<Scan> | null>(null);
  readonly savedSearches = signal<SavedSearch[]>([]);
  readonly groups = signal<ScenarioPage<ScenarioGroup> | null>(null);
  readonly decisions = signal<ScenarioPage<ScenarioDecision> | null>(null);
  readonly selectedGroup = signal<ScenarioGroup | null>(null);
  readonly problem = signal<ApiError | null>(null);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly groupLoading = signal(false);
  readonly decisionLoading = signal(false);
  readonly scanLoading = signal(false);
  readonly listLoading = signal(false);
  readonly directoryLabel = signal('');
  readonly referenceLabel = signal('');
  readonly downloadNotice = signal('');
  exportFormat: 'JSON' | 'JSONL' | 'CSV' = 'JSONL';
  form = scenarioForm();
  rules: RetentionRule[] = [{ kind: 'SHALLOWEST' }];
  protections: ProtectedPath[] = [];
  dirty = true;
  scanNameFilter = '';
  savedSearchId = '';

  ngOnInit(): void {
    this.loadList(null); this.loadScans(null);
    this.subscriptions.add(this.api.savedSearches().subscribe({
      next: page => this.savedSearches.set(page.items), error: error => this.fail(error)
    }));
  }
  ngOnChanges(): void {
    this.downloadEpoch++;
    this.recordRequest?.unsubscribe(); this.groupRequest?.unsubscribe(); this.decisionRequest?.unsubscribe();
    this.busy.set(false); this.problem.set(null); this.downloadNotice.set(''); this.clearSnapshot();
    const id = this.initialId();
    if (id) {
      this.loading.set(true); this.record.set(null);
      this.recordRequest = this.api.scenario(id).subscribe({
        next: item => { this.setRecord(item); this.loading.set(false); if (item.generationId) this.loadGroups(null); },
        error: error => { this.fail(error); this.loading.set(false); }
      });
    } else this.startForm(this.seed() ?? { scanIds: [] });
  }
  ngOnDestroy(): void {
    this.downloadEpoch++;
    this.recordRequest?.unsubscribe(); this.listRequest?.unsubscribe(); this.scanRequest?.unsubscribe();
    this.groupRequest?.unsubscribe(); this.decisionRequest?.unsubscribe(); this.subscriptions.unsubscribe();
    this.labelRequests.unsubscribe();
  }
  startNew(): void { this.startForm({ scanIds: [] }); this.scenarioOpen.emit(null); }
  save(event: Event): void {
    event.preventDefault();
    let config: ScenarioConfig;
    try {
      if (!this.form.name.trim()) throw new Error('Enter a scenario name.');
      config = this.buildConfig();
    } catch (error) { this.localError(error); return; }
    const current = this.record();
    this.act(this.api.saveScenario(current?.id ?? null, { name: this.form.name, description: this.form.description,
      config, ...(current ? { revision: current.revision } : {}) }), item => {
      this.setRecord(item); this.loadList(null); this.scenarioOpen.emit(item.id);
      if (item.generationId) this.loadGroups(null);
    });
  }
  generate(): void { this.action('generate'); }
  validate(): void { this.action('validate'); }
  resetOverrides(): void { this.action('overrides/reset'); }
  canExport(): boolean {
    const current = this.record();
    return !!current?.snapshot?.validation.valid && !current.stale && !this.dirty && !this.busy() && !this.loading();
  }
  exportManifest(): void {
    const current = this.record(); if (!current || !this.canExport()) return;
    this.busy.set(true); this.problem.set(null); this.downloadNotice.set(''); this.recordRequest?.unsubscribe();
    const epoch = ++this.downloadEpoch;
    const format = this.exportFormat;
    this.recordRequest = this.api.exportScenario(current.id, current.revision, format).subscribe({
      next: response => {
        if (response.body) {
          const url = URL.createObjectURL(response.body);
          const anchor = document.createElement('a');
          anchor.href = url; anchor.download = `scenario-${current.id}-r${current.revision}.${format.toLowerCase()}`;
          document.body.appendChild(anchor); anchor.click(); anchor.remove();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
          this.downloadNotice.set(this.copy.exportDownloaded);
        }
        this.recordRequest = this.api.scenario(current.id).subscribe({
          next: item => { this.busy.set(false); this.record.set(item); this.loadList(null); },
          error: error => { this.busy.set(false); this.fail(error); }
        });
      },
      error: async (error: HttpErrorResponse) => {
        // Download error bodies use JSON, even though the successful response is a Blob.
        let body: Partial<ApiError> | null = error.error;
        if (error.error instanceof Blob) {
          try { body = JSON.parse(await error.error.text()) as ApiError; } catch { body = null; }
        }
        if (this.downloadEpoch !== epoch || this.record()?.id !== current.id) return;
        this.busy.set(false);
        this.problem.set({ code: body?.code ?? 'REQUEST_FAILED', message: body?.message ?? this.copy.failed });
        this.recordRequest = this.api.scenario(current.id).subscribe({
          next: item => { this.record.set(item); this.loadList(null); }, error: failure => this.fail(failure)
        });
      }
    });
  }
  delete(): void {
    const current = this.record(); if (!current) return;
    this.busy.set(true); this.problem.set(null);
    this.recordRequest = this.api.deleteScenario(current.id, current.revision).subscribe({
      next: () => { this.busy.set(false); this.startNew(); this.loadList(null); },
      error: error => { this.busy.set(false); this.fail(error); }
    });
  }
  override(row: ScenarioDecision, choice: string): void {
    const current = this.record(); if (!current || !this.canEditDecisions()) return;
    this.act(this.api.scenarioOverride(current.id, current.revision, row.scanId, row.entryId, choice), item => {
      this.record.set(item); this.loadList(null);
      this.groupHistory = []; this.decisionHistory = [];
      this.loadDecisions(null); this.loadGroups(null, false);
    });
  }
  canEditDecisions(): boolean {
    const current = this.record();
    return !!current?.generationId && !current.definitionStale && !current.snapshot?.sourceChanged && !this.dirty && !this.busy();
  }
  canRemove(row: ScenarioDecision): boolean { return !row.protected && !row.conflicting && !row.hasError && row.matchesFilters; }
  useSavedSearch(): void {
    const item = this.savedSearches().find(x => x.id === this.savedSearchId); if (!item) return;
    try {
      const request = requestFromSearch({ ...item.request,
        scanIds: item.request.scanIds?.length ? item.request.scanIds : this.form.scanIds });
      const previous = this.form;
      this.scope = structuredClone(request);
      this.form = { ...scenarioForm(request), name: previous.name, description: previous.description,
        manual: previous.manual, maxOccurrences: previous.maxOccurrences, maxSeconds: previous.maxSeconds };
      this.dirty = true; this.problem.set(null); this.loadScopeLabels();
    } catch (error) { this.localError(error); }
  }
  clearDirectory(): void { delete this.scope.directory; this.loadScopeLabels(); this.dirty = true; }
  clearReference(): void { delete this.scope.entry; this.loadScopeLabels(); this.dirty = true; }
  addRule(): void { if (this.rules.length < 32) { this.rules.push({ kind: 'SHALLOWEST' }); this.dirty = true; } }
  moveRule(index: number, offset: number): void {
    const other = index + offset; if (other < 0 || other >= this.rules.length) return;
    [this.rules[index], this.rules[other]] = [this.rules[other], this.rules[index]]; this.dirty = true;
  }
  removeRule(index: number): void { this.rules.splice(index, 1); this.dirty = true; }
  addProtection(): void { if (this.protections.length < 100) { this.protections.push({ path: '' }); this.dirty = true; } }
  removeProtection(index: number): void { this.protections.splice(index, 1); this.dirty = true; }
  scanLabel(id: number): string { return this.scanNames.get(id) ?? `${copy.scanLabel} ${id}`; }
  reason(value: string): string { return scenarioCopy.reasons[value] ?? value; }
  openDirectory(row: ScenarioDecision): void {
    if (row.parentId > 0) this.directoryOpen.emit({ scanId: row.scanId, entryId: row.parentId });
  }

  nextList(): void {
    const next = this.list()?.page.nextCursor;
    if (next) { this.listHistory.push(this.listCursor); this.loadList(next); }
  }
  previousList(): void { if (this.listHistory.length) this.loadList(this.listHistory.pop() ?? null); }
  canPreviousList(): boolean { return this.listHistory.length > 0; }
  findScans(): void { this.scanFilter = this.scanNameFilter; this.scanHistory = []; this.loadScans(null); }
  nextScans(): void {
    const next = this.scans()?.page.nextCursor;
    if (next) { this.scanHistory.push(this.scanCursor); this.loadScans(next); }
  }
  previousScans(): void { if (this.scanHistory.length) this.loadScans(this.scanHistory.pop() ?? null); }
  canPreviousScans(): boolean { return this.scanHistory.length > 0; }
  selectScans(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const visible = new Set(this.scans()?.items.map(x => x.scanId));
    const selected = [...this.form.scanIds.filter(id => !visible.has(id)),
      ...Array.from(select.selectedOptions).map(x => Number(x.value))];
    if (selected.length > 1000) {
      this.localError(new Error(copy.tooManySelectedScans));
      for (const option of Array.from(select.options)) option.selected = this.form.scanIds.includes(Number(option.value));
      return;
    }
    this.form.scanIds = selected; this.dirty = true;
  }
  removeScan(id: number): void { this.form.scanIds = this.form.scanIds.filter(x => x !== id); this.dirty = true; }
  nextGroups(): void {
    const next = this.groups()?.page.nextCursor;
    if (next) { this.groupHistory.push(this.groupCursor); this.loadGroups(next); }
  }
  previousGroups(): void { if (this.groupHistory.length) this.loadGroups(this.groupHistory.pop() ?? null); }
  canPreviousGroups(): boolean { return this.groupHistory.length > 0; }
  review(group: ScenarioGroup): void { this.selectedGroup.set(group); this.decisionHistory = []; this.loadDecisions(null); }
  closeGroup(): void { this.decisionRequest?.unsubscribe(); this.selectedGroup.set(null); this.decisions.set(null); this.decisionLoading.set(false); }
  nextDecisions(): void {
    const next = this.decisions()?.page.nextCursor;
    if (next) { this.decisionHistory.push(this.decisionCursor); this.loadDecisions(next); }
  }
  previousDecisions(): void { if (this.decisionHistory.length) this.loadDecisions(this.decisionHistory.pop() ?? null); }
  canPreviousDecisions(): boolean { return this.decisionHistory.length > 0; }

  private buildConfig(): ScenarioConfig {
    const request = scopeFromForm(this.form, this.scope);
    if (!Number.isSafeInteger(this.form.maxOccurrences) || this.form.maxOccurrences < 1 || this.form.maxOccurrences > 1000000 ||
        !Number.isSafeInteger(this.form.maxSeconds) || this.form.maxSeconds < 1 || this.form.maxSeconds > 600)
      throw new Error('Use 1..1,000,000 observations and 1..600 seconds.');
    const rules = this.rules.map(rule => {
      if (rule.kind === 'PREFER_SCAN') {
        if (!rule.scanId || !request.scanIds.includes(rule.scanId)) throw new Error('Choose a selected scan for the retention rule.');
        return { kind: rule.kind, scanId: rule.scanId };
      }
      if (rule.kind === 'PREFER_PATH') return { kind: rule.kind, scanId: rule.scanId, path: rule.path ?? '' };
      return { kind: rule.kind };
    });
    return { request, rules, protections: structuredClone(this.protections), manual: this.form.manual,
      maxOccurrences: this.form.maxOccurrences, maxSeconds: this.form.maxSeconds };
  }
  private startForm(request: DuplicateRequest): void {
    this.record.set(null); this.downloadNotice.set(''); this.clearSnapshot(); this.scope = structuredClone(request); this.form = scenarioForm(request);
    this.rules = [{ kind: 'SHALLOWEST' }]; this.protections = []; this.dirty = true;
    this.loading.set(false); this.problem.set(null); this.loadScopeLabels();
  }
  private setRecord(item: Scenario): void {
    this.record.set(item); this.scope = structuredClone(item.config.request);
    this.form = { ...applyConfig(scenarioForm(item.config.request), item.config), name: item.name, description: item.description };
    this.rules = structuredClone(item.config.rules); this.protections = structuredClone(item.config.protections); this.dirty = false;
    item.snapshot?.sourceScans.forEach(scan => this.scanNames.set(scan.scanId, scan.name));
    this.loadScopeLabels();
  }
  private clearSnapshot(): void { this.closeGroup(); this.groups.set(null); this.groupHistory = []; this.groupLoading.set(false); }
  private loadScopeLabels(): void {
    this.labelRequests.unsubscribe(); this.labelRequests = new Subscription();
    this.directoryLabel.set(''); this.referenceLabel.set('');
    if (this.scope.directory) {
      const { scanId, entryId } = this.scope.directory;
      this.labelRequests.add(this.api.entry(scanId, entryId).subscribe({
        next: entry => this.directoryLabel.set(entry.relativePath || '/'), error: error => this.fail(error)
      }));
    }
    if (this.scope.entry) {
      const { scanId, entryId } = this.scope.entry;
      this.labelRequests.add(this.api.entry(scanId, entryId).subscribe({
        next: entry => this.referenceLabel.set(entry.relativePath), error: error => this.fail(error)
      }));
    }
  }
  private loadList(cursor: string | null): void {
    this.listRequest?.unsubscribe(); this.listCursor = cursor; this.listLoading.set(true);
    this.listRequest = this.api.scenarios(cursor).subscribe({
      next: page => { this.list.set(page); this.listLoading.set(false); },
      error: error => { this.fail(error); this.listLoading.set(false); }
    });
  }
  private loadScans(cursor: string | null): void {
    this.scanRequest?.unsubscribe(); this.scanCursor = cursor; this.scanLoading.set(true);
    this.scanRequest = this.api.scans(cursor, { name: this.scanFilter, phase: '', hasErrors: '' }, 100).subscribe({
      next: page => {
        page.items.forEach(scan => this.scanNames.set(scan.scanId, scan.name));
        const retained = new Set([...this.form.scanIds, ...page.items.map(x => x.scanId)]);
        for (const id of this.scanNames.keys()) if (!retained.has(id)) this.scanNames.delete(id);
        this.scans.set(page); this.scanLoading.set(false);
      }, error: error => { this.fail(error); this.scanLoading.set(false); }
    });
  }
  private loadGroups(cursor: string | null, clear = true): void {
    const current = this.record(); if (!current?.generationId) return;
    this.groupRequest?.unsubscribe(); this.groupCursor = cursor; this.groupLoading.set(true);
    if (clear) this.closeGroup();
    this.groupRequest = this.api.scenarioGroups(current.id, cursor).subscribe({
      next: page => { this.groups.set(page); this.groupLoading.set(false); },
      error: error => { this.fail(error); this.groupLoading.set(false); }
    });
  }
  private loadDecisions(cursor: string | null): void {
    const current = this.record(), group = this.selectedGroup(); if (!current || !group) return;
    this.decisionRequest?.unsubscribe(); this.decisionCursor = cursor; this.decisions.set(null); this.decisionLoading.set(true);
    this.decisionRequest = this.api.scenarioDecisions(current.id, group.groupId, cursor).subscribe({
      next: page => { this.decisions.set(page); this.decisionLoading.set(false); },
      error: error => { this.fail(error); this.decisionLoading.set(false); }
    });
  }
  private action(action: 'generate' | 'validate' | 'overrides/reset'): void {
    const current = this.record(); if (!current || this.dirty) return;
    this.act(this.api.scenarioAction(current.id, action, current.revision), item => {
      this.setRecord(item); this.groupHistory = []; this.loadList(null); this.loadGroups(null);
    });
  }
  private act(request: Observable<Scenario>, success: (item: Scenario) => void): void {
    this.busy.set(true); this.problem.set(null); this.recordRequest?.unsubscribe();
    this.recordRequest = request.subscribe({
      next: item => { this.busy.set(false); success(item); },
      error: error => { this.busy.set(false); this.fail(error); }
    });
  }
  private fail(error: HttpErrorResponse): void {
    const body = error.error as Partial<ApiError> | null;
    this.problem.set({ code: body?.code ?? 'REQUEST_FAILED', message: body?.message ?? copy.failed });
  }
  private localError(error: unknown): void { this.problem.set({ code: 'INVALID_SCENARIO', message: (error as Error).message }); }
}
