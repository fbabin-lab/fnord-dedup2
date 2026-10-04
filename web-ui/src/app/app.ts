import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, forkJoin } from 'rxjs';
import { copy } from './copy';
import { DirectoryTreeComponent } from './directory-tree';
import { FileSearchComponent } from './file-search';
import { DuplicateExplorerComponent } from './duplicate-explorer';
import { ScenarioBuilderComponent } from './scenario-builder';
import { InventoryApi } from './inventory.api';
import {
  ApiError, Breadcrumb, ChildPage, Dashboard, DatabaseStatus, DuplicateRequest, Entry, Page,
  Registration, Scan, ScanError, ScanFilters
} from './inventory.models';

type View = 'dashboard' | 'scans' | 'scan' | 'explorer' | 'search' | 'duplicates' | 'scenarios' | 'errors';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [DirectoryTreeComponent, FileSearchComponent, DuplicateExplorerComponent, ScenarioBuilderComponent],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly api = inject(InventoryApi);
  private pageRequests = new Subscription();
  private statusRequest?: Subscription;
  private detailRequest?: Subscription;
  private routeKey = '';
  private readonly onHashChange = () => this.syncRoute();
  private scanCursor: string | null = null;
  private scanHistory: (string | null)[] = [];
  private directoryCursor: string | null = null;
  private directoryHistory: (string | null)[] = [];
  private errorCursor: string | null = null;
  private errorHistory: (string | null)[] = [];

  readonly copy = copy;
  readonly view = signal<View>('dashboard');
  readonly scanId = signal<number | null>(null);
  readonly directoryId = signal(1);
  readonly searchDirectoryId = signal<number | null>(null);
  readonly duplicateEntryId = signal<number | null>(null);
  readonly scenarioId = signal<string | null>(null);
  readonly scenarioSeed = signal<DuplicateRequest | null>(null);
  readonly registration = signal<Registration | null>(null);
  readonly status = signal<DatabaseStatus | null>(null);
  readonly dashboard = signal<Dashboard | null>(null);
  readonly scans = signal<Page<Scan> | null>(null);
  readonly scan = signal<Scan | null>(null);
  readonly children = signal<ChildPage | null>(null);
  readonly breadcrumbs = signal<Breadcrumb[]>([]);
  readonly errors = signal<Page<ScanError> | null>(null);
  readonly selectedEntry = signal<Entry | null>(null);
  readonly loading = signal(false);
  readonly detailLoading = signal(false);
  readonly problem = signal<ApiError | null>(null);
  readonly statusProblem = signal<ApiError | null>(null);
  readonly detailProblem = signal<ApiError | null>(null);
  readonly scanFilters: ScanFilters = { name: '', phase: '', hasErrors: '' };

  ngOnInit(): void {
    window.addEventListener('hashchange', this.onHashChange);
    this.api.registration().subscribe({
      next: value => this.registration.set(value),
      error: () => this.registration.set(null)
    });
    this.refreshStatus();
    this.syncRoute();
  }
  ngOnDestroy(): void {
    window.removeEventListener('hashchange', this.onHashChange);
    this.pageRequests.unsubscribe();
    this.statusRequest?.unsubscribe();
    this.detailRequest?.unsubscribe();
  }

  navigate(path: string): void { window.location.hash = '#' + path; this.syncRoute(); }
  openScan(id: number): void { this.navigate('/scans/' + id); }
  openExplorer(id?: number): void {
    const target = id ?? this.scanId();
    if (target) this.navigate('/scans/' + target + '/explore/1');
    else this.navigate('/scans');
  }
  openDirectory(entryId: number): void {
    const id = this.scanId();
    if (id) this.navigate('/scans/' + id + '/explore/' + entryId);
  }
  openSearchDirectory(location: { scanId: number; entryId: number }): void {
    this.navigate('/scans/' + location.scanId + '/explore/' + location.entryId);
  }
  openSearch(scanId?: number, entryId?: number): void {
    const path = scanId ? '/search/' + scanId + (entryId ? '/' + entryId : '') : '/search';
    this.navigate(path);
  }
  openDuplicates(scanId?: number, entryId?: number): void {
    this.navigate(scanId ? '/duplicates/' + scanId + (entryId ? '/' + entryId : '') : '/duplicates');
  }
  findDuplicates(entry: Entry): void {
    const id = entry.scanId ?? this.scanId();
    if (id) this.openDuplicates(id, entry.entryId);
  }
  openScenarios(): void { this.scenarioSeed.set(null); this.navigate('/scenarios'); }
  openScenario(id: string | null): void {
    this.scenarioSeed.set(null); this.navigate('/scenarios/' + (id ?? 'new'));
  }
  createScenario(request: DuplicateRequest): void {
    this.scenarioSeed.set(structuredClone(request)); this.navigate('/scenarios/new');
  }
  scenarioForScan(scanId: number, entryId?: number): void {
    this.createScenario({ scanIds: [scanId],
      ...(entryId ? { directory: { scanId, entryId, recursive: true } } : {}) });
  }
  openErrors(id?: number): void {
    const target = id ?? this.scanId();
    if (target) this.navigate('/scans/' + target + '/errors');
    else this.navigate('/scans');
  }
  openEntry(entry: Entry): void {
    const scanId = entry.scanId ?? this.scanId();
    if (entry.kind === 'DIRECTORY') {
      if (scanId) this.navigate('/scans/' + scanId + '/explore/' + entry.entryId);
      return;
    }
    if (!scanId) return;
    this.detailRequest?.unsubscribe();
    this.selectedEntry.set(null);
    this.detailProblem.set(null);
    this.detailLoading.set(true);
    this.detailRequest = this.api.entry(scanId, entry.entryId).subscribe({
      next: value => { this.selectedEntry.set({ ...value, scanId, scanName: entry.scanName }); this.detailLoading.set(false); },
      error: failure => { this.detailProblem.set(this.apiError(failure)); this.detailLoading.set(false); }
    });
  }
  closeEntry(): void {
    this.detailRequest?.unsubscribe();
    this.selectedEntry.set(null);
    this.detailProblem.set(null);
    this.detailLoading.set(false);
  }

  refresh(): void { this.refreshStatus(); this.loadCurrent(); }
  refreshStatus(): void {
    this.statusRequest?.unsubscribe();
    this.statusProblem.set(null);
    this.statusRequest = this.api.status().subscribe({
      next: value => this.status.set(value),
      error: failure => { this.status.set(null); this.statusProblem.set(this.apiError(failure)); }
    });
  }

  applyScanFilters(event: Event): void {
    event.preventDefault();
    this.scanHistory = []; this.scanCursor = null;
    this.loadScanPage();
  }
  nextScans(): void {
    const next = this.scans()?.page.nextCursor;
    if (!next) return;
    this.scanHistory.push(this.scanCursor); this.scanCursor = next; this.loadScanPage();
  }
  previousScans(): void {
    if (!this.scanHistory.length) return;
    this.scanCursor = this.scanHistory.pop() ?? null; this.loadScanPage();
  }
  canPreviousScans(): boolean { return this.scanHistory.length > 0; }
  nextDirectory(): void {
    const next = this.children()?.page.nextCursor;
    if (!next) return;
    this.directoryHistory.push(this.directoryCursor); this.directoryCursor = next;
    this.loadDirectoryPage();
  }
  previousDirectory(): void {
    if (!this.directoryHistory.length) return;
    this.directoryCursor = this.directoryHistory.pop() ?? null; this.loadDirectoryPage();
  }
  canPreviousDirectory(): boolean { return this.directoryHistory.length > 0; }
  nextErrors(): void {
    const next = this.errors()?.page.nextCursor;
    if (!next) return;
    this.errorHistory.push(this.errorCursor); this.errorCursor = next; this.loadErrorsPage();
  }
  previousErrors(): void {
    if (!this.errorHistory.length) return;
    this.errorCursor = this.errorHistory.pop() ?? null; this.loadErrorsPage();
  }
  canPreviousErrors(): boolean { return this.errorHistory.length > 0; }

  formatBytes(value: string | number): string {
    const bytes = BigInt(value);
    if (bytes < 1024n) return bytes.toString() + ' B';
    const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
    let divisor = 1024n, index = 0;
    while (index < units.length - 1 && bytes >= divisor * 1024n) {
      divisor *= 1024n; index++;
    }
    const tenth = (bytes % divisor) * 10n / divisor;
    return (bytes / divisor).toString() + '.' + tenth.toString() + ' ' + units[index];
  }
  formatDate(value: string): string { return new Date(value).toLocaleString(); }
  formatModified(entry: Entry): string {
    return new Date(entry.modifiedSec * 1000 + Math.floor(entry.modifiedNano / 1_000_000)).toLocaleString();
  }
  formatErrorDate(value: number): string { return new Date(value).toLocaleString(); }

  private syncRoute(): void {
    const path = window.location.hash.slice(1) || '/dashboard';
    if (path === this.routeKey) return;
    this.routeKey = path;
    const parts = path.split('/').filter(Boolean);
    const id = Number(parts[1]), entryId = Number(parts[3]), searchEntryId = Number(parts[2]);
    this.searchDirectoryId.set(null);
    this.duplicateEntryId.set(null);
    this.scenarioId.set(null);
    if (parts[0] === 'scans' && parts.length === 1) {
      this.view.set('scans'); this.scanId.set(null);
    } else if (parts[0] === 'search' && parts.length === 1) {
      this.view.set('search'); this.scanId.set(null);
    } else if (parts[0] === 'search' && Number.isSafeInteger(id) && id > 0 &&
               (parts.length === 2 || (parts.length === 3 &&
                Number.isSafeInteger(searchEntryId) && searchEntryId > 0))) {
      this.view.set('search'); this.scanId.set(id);
      this.searchDirectoryId.set(parts.length === 3 ? searchEntryId : null);
    } else if (parts[0] === 'duplicates' && parts.length === 1) {
      this.view.set('duplicates'); this.scanId.set(null);
    } else if (parts[0] === 'duplicates' && Number.isSafeInteger(id) && id > 0 &&
               (parts.length === 2 || (parts.length === 3 &&
                Number.isSafeInteger(searchEntryId) && searchEntryId > 0))) {
      this.view.set('duplicates'); this.scanId.set(id);
      this.duplicateEntryId.set(parts.length === 3 ? searchEntryId : null);
    } else if (parts[0] === 'scenarios' && (parts.length === 1 ||
               (parts.length === 2 && (parts[1] === 'new' || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(parts[1]))))) {
      this.view.set('scenarios'); this.scanId.set(null);
      this.scenarioId.set(parts.length === 2 && parts[1] !== 'new' ? parts[1].toLowerCase() : null);
    } else if (parts[0] === 'scans' && Number.isSafeInteger(id) && id > 0 &&
               parts.length === 2) {
      this.view.set('scan'); this.scanId.set(id);
    } else if (parts[0] === 'scans' && Number.isSafeInteger(id) && id > 0 &&
               parts[2] === 'explore' && Number.isSafeInteger(entryId) && entryId > 0 &&
               parts.length === 4) {
      this.view.set('explorer'); this.scanId.set(id); this.directoryId.set(entryId);
    } else if (parts[0] === 'scans' && Number.isSafeInteger(id) && id > 0 &&
               parts[2] === 'errors' && parts.length === 3) {
      this.view.set('errors'); this.scanId.set(id);
    } else {
      this.view.set('dashboard'); this.scanId.set(null);
    }
    this.scanHistory = []; this.scanCursor = null;
    this.directoryHistory = []; this.directoryCursor = null;
    this.errorHistory = []; this.errorCursor = null;
    this.closeEntry();
    this.loadCurrent();
  }
  private loadCurrent(): void {
    this.cancelPage();
    this.dashboard.set(null); this.scans.set(null); this.scan.set(null);
    this.children.set(null); this.errors.set(null); this.breadcrumbs.set([]);
    if (this.view() === 'dashboard') {
      this.watch(forkJoin({
        metrics: this.api.dashboard(),
        recent: this.api.scans(null, { name: '', phase: '', hasErrors: '' }, 10)
      }), result => { this.dashboard.set(result.metrics); this.scans.set(result.recent); });
    } else if (this.view() === 'scans') this.loadScanPage();
    else if (this.view() === 'search' || this.view() === 'duplicates' || this.view() === 'scenarios') this.loading.set(false);
    else {
      const id = this.scanId();
      if (!id) return;
      if (this.view() === 'scan') this.watch(this.api.scan(id), result => this.scan.set(result));
      if (this.view() === 'explorer') {
        this.watch(forkJoin({
          scan: this.api.scan(id),
          children: this.api.children(id, this.directoryId(), null),
          breadcrumbs: this.api.breadcrumbs(id, this.directoryId())
        }), result => {
          this.scan.set(result.scan);
          this.children.set(result.children);
          this.breadcrumbs.set(result.breadcrumbs);
        });
      }
      if (this.view() === 'errors') {
        this.watch(forkJoin({ scan: this.api.scan(id), errors: this.api.errors(id, null) }),
          result => { this.scan.set(result.scan); this.errors.set(result.errors); });
      }
    }
  }
  private loadScanPage(): void {
    this.cancelPage();
    this.watch(this.api.scans(this.scanCursor, this.scanFilters), page => this.scans.set(page));
  }
  private loadDirectoryPage(): void {
    const id = this.scanId();
    if (!id) return;
    this.cancelPage();
    this.watch(this.api.children(id, this.directoryId(), this.directoryCursor),
      page => this.children.set(page));
  }
  private loadErrorsPage(): void {
    const id = this.scanId();
    if (!id) return;
    this.cancelPage();
    this.watch(this.api.errors(id, this.errorCursor), page => this.errors.set(page));
  }
  private cancelPage(): void {
    this.pageRequests.unsubscribe();
    this.pageRequests = new Subscription();
    this.loading.set(true);
    this.problem.set(null);
  }
  private watch<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.pageRequests.add(request.subscribe({
      next: value => { onSuccess(value); this.loading.set(false); },
      error: failure => { this.problem.set(this.apiError(failure)); this.loading.set(false); }
    }));
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
