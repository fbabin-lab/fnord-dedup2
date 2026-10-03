import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { copy } from './copy';

interface DatabaseStatus {
  path: string;
  baseSchema: number;
  archiveSchema: number | null;
  imageSchema: number | null;
  supported: boolean;
  scanCount: number;
  lastModified: string;
  readOnly: boolean;
}

interface Registration { path: string | null; configured: boolean; readOnly: boolean; }
interface ApiError { code: string; message: string; }

@Component({
  selector: 'app-root',
  standalone: true,
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly requests = new Subscription();
  private statusRequest?: Subscription;
  readonly copy = copy;
  readonly registration = signal<Registration | null>(null);
  readonly status = signal<DatabaseStatus | null>(null);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.requests.add(this.http.get<Registration>('/api/v1/database').subscribe({
      next: value => this.registration.set(value)
    }));
    this.refresh();
  }

  refresh(): void {
    this.statusRequest?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.statusRequest = this.http.get<DatabaseStatus>('/api/v1/database/status').subscribe({
      next: value => {
        this.status.set(value);
        this.loading.set(false);
      },
      error: (failure: HttpErrorResponse) => {
        this.status.set(null);
        this.loading.set(false);
        const response = failure.error as Partial<ApiError> | null;
        this.error.set({
          code: response?.code ?? 'REQUEST_FAILED',
          message: failure.status === 423 ? copy.locked : response?.message ?? copy.failed
        });
      }
    });
  }

  ngOnDestroy(): void {
    this.statusRequest?.unsubscribe();
    this.requests.unsubscribe();
  }
}
