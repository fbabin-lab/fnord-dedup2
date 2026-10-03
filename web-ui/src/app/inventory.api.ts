import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Breadcrumb, ChildPage, Dashboard, DatabaseStatus, Entry, Page, Registration,
  Scan, ScanError, ScanFilters
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
}
