import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Breadcrumb, ChildPage, Dashboard, DatabaseStatus, DuplicateOccurrencePage,
  DuplicatePage, DuplicateRequest, Entry, FileSearchPage,
  FileOccurrence, FileSearchRequest, Page, Registration, SavedSearch, SavedSearchList, Scan,
  ScanError, ScanFilters, Scenario, ScenarioConfig, ScenarioListItem, ScenarioGroup,
  ScenarioDecision, ScenarioPage
} from './inventory.models';

@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly http = inject(HttpClient);

  registration() { return this.http.get<Registration>('/api/v1/database'); }
  status() { return this.http.get<DatabaseStatus>('/api/v1/database/status'); }
  dashboard() { return this.http.get<Dashboard>('/api/v1/dashboard'); }
  scans(cursor: string | null, filters: ScanFilters, limit = 25) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('after', cursor);
    if (filters.name.trim()) params = params.set('name', filters.name.trim());
    if (filters.phase) params = params.set('phase', filters.phase);
    if (filters.hasErrors) params = params.set('hasErrors', filters.hasErrors);
    return this.http.get<Page<Scan>>('/api/v1/scans', { params });
  }
  scan(scanId: number) { return this.http.get<Scan>('/api/v1/scans/' + scanId); }
  entry(scanId: number, entryId: number) {
    return this.http.get<Entry>('/api/v1/scans/' + scanId + '/entries/' + entryId);
  }
  entryOccurrences(scanId: number, entryId: number, cursor: string | null, limit = 20) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<Page<FileOccurrence>>(
      '/api/v1/scans/' + scanId + '/entries/' + entryId + '/occurrences', { params });
  }
  children(scanId: number, entryId: number, cursor: string | null, limit = 100) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ChildPage>(
      '/api/v1/scans/' + scanId + '/entries/' + entryId + '/children', { params });
  }
  breadcrumbs(scanId: number, entryId: number) {
    return this.http.get<Breadcrumb[]>(
      '/api/v1/scans/' + scanId + '/entries/' + entryId + '/breadcrumbs');
  }
  errors(scanId: number, cursor: string | null, limit = 100) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<Page<ScanError>>('/api/v1/scans/' + scanId + '/errors', { params });
  }
  searchFiles(request: FileSearchRequest) {
    return this.http.post<FileSearchPage>('/api/v1/search/files', request);
  }
  duplicateGroups(request: DuplicateRequest) {
    return this.http.post<DuplicatePage>('/api/v1/duplicates/groups', request);
  }
  duplicateOccurrences(groupId: string, request: DuplicateRequest) {
    return this.http.post<DuplicateOccurrencePage>(
      '/api/v1/duplicates/groups/' + encodeURIComponent(groupId) + '/occurrences', request);
  }
  savedSearches() { return this.http.get<SavedSearchList>('/api/v1/saved-searches'); }
  createSavedSearch(body: { name: string; description: string; request: FileSearchRequest }) {
    return this.http.post<SavedSearch>('/api/v1/saved-searches', body);
  }
  updateSavedSearch(id: string, body: { name: string; description: string; request: FileSearchRequest }) {
    return this.http.put<SavedSearch>('/api/v1/saved-searches/' + encodeURIComponent(id), body);
  }
  deleteSavedSearch(id: string) {
    return this.http.delete<void>('/api/v1/saved-searches/' + encodeURIComponent(id));
  }
  scenarios(cursor: string | null, limit = 50) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<Page<ScenarioListItem>>('/api/v1/scenarios', { params });
  }
  scenario(id: string) { return this.http.get<Scenario>('/api/v1/scenarios/' + encodeURIComponent(id)); }
  saveScenario(id: string | null, body: { name: string; description: string; config: ScenarioConfig; revision?: number }) {
    return id ? this.http.put<Scenario>('/api/v1/scenarios/' + encodeURIComponent(id), body) :
      this.http.post<Scenario>('/api/v1/scenarios', body);
  }
  deleteScenario(id: string, revision: number) {
    return this.http.delete<void>('/api/v1/scenarios/' + encodeURIComponent(id), { params: new HttpParams().set('revision', revision) });
  }
  scenarioAction(id: string, action: 'generate' | 'validate' | 'overrides/reset', revision: number) {
    return this.http.post<Scenario>('/api/v1/scenarios/' + encodeURIComponent(id) + '/' + action, { revision });
  }
  scenarioOverride(id: string, revision: number, scanId: number, entryId: number, decision: string) {
    return this.http.post<Scenario>('/api/v1/scenarios/' + encodeURIComponent(id) + '/overrides',
      { revision, decisions: [{ scanId, entryId, decision }] });
  }
  scenarioGroups(id: string, cursor: string | null, limit = 100) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ScenarioPage<ScenarioGroup>>('/api/v1/scenarios/' + encodeURIComponent(id) + '/groups', { params });
  }
  scenarioDecisions(id: string, group: string, cursor: string | null, limit = 100) {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ScenarioPage<ScenarioDecision>>('/api/v1/scenarios/' + encodeURIComponent(id) + '/groups/' +
      encodeURIComponent(group) + '/decisions', { params });
  }
}
