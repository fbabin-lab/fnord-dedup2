import { Component, OnDestroy, OnInit, inject, output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { CandidateBadgesComponent, CandidateLegendComponent } from './candidate-badges';
import { SignatureEditorComponent } from './signature-editor';
import { InventoryApi } from './inventory.api';
import { Entry, Page, Signature, SignatureMatches } from './inventory.models';
import { formatBytes } from './file-values';

@Component({
  selector: 'app-signature-store',
  standalone: true,
  imports: [SignatureEditorComponent, CandidateBadgesComponent, CandidateLegendComponent],
  templateUrl: './signature-store.html',
  styleUrls: ['./file-search.css', './signature-store.css']
})
export class SignatureStoreComponent implements OnInit, OnDestroy {
  private readonly api = inject(InventoryApi);
  private readonly subscriptions = new Subscription();
  private listRequest?: Subscription;
  private matchRequest?: Subscription;
  private cursor: string | null = null;
  private history: (string | null)[] = [];
  private matchCursor: string | null = null;
  private matchHistory: (string | null)[] = [];
  readonly entryOpen = output<Entry>();
  readonly directoryOpen = output<{ scanId: number; entryId: number }>();
  readonly entries = signal<Page<Signature> | null>(null);
  readonly matches = signal<SignatureMatches | null>(null);
  readonly selected = signal<Signature | null>(null);
  readonly editing = signal<Signature | null>(null);
  readonly deleting = signal<Signature | null>(null);
  readonly loading = signal(false);
  readonly matchLoading = signal(false);
  readonly deleteBusy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly matchProblem = signal<string | null>(null);
  readonly formatBytes = formatBytes;

  ngOnInit(): void {
    this.load();
    this.subscriptions.add(this.api.signatureChanges.subscribe(change => {
      if (this.selected()?.id === change.signature.id) {
        if (change.removed) this.closeMatches();
        else { this.selected.set(change.signature); this.loadMatches(); }
      }
      this.load();
    }));
  }
  ngOnDestroy(): void { this.listRequest?.unsubscribe(); this.matchRequest?.unsubscribe(); this.subscriptions.unsubscribe(); }
  refresh(): void { this.load(); if (this.selected()) this.loadMatches(); }
  next(): void { const next = this.entries()?.page.nextCursor; if (next) { this.history.push(this.cursor); this.cursor = next; this.load(); } }
  previous(): void { if (this.history.length) { this.cursor = this.history.pop() ?? null; this.load(); } }
  canPrevious(): boolean { return this.history.length > 0; }
  openMatches(signature: Signature): void {
    this.selected.set(signature); this.matches.set(null); this.matchCursor = null; this.matchHistory = []; this.loadMatches();
  }
  closeMatches(): void { this.matchRequest?.unsubscribe(); this.selected.set(null); this.matches.set(null); this.matchProblem.set(null); this.matchLoading.set(false); }
  nextMatches(): void {
    const next = this.matches()?.page.nextCursor;
    if (next) { this.matchHistory.push(this.matchCursor); this.matchCursor = next; this.loadMatches(); }
  }
  previousMatches(): void { if (this.matchHistory.length) { this.matchCursor = this.matchHistory.pop() ?? null; this.loadMatches(); } }
  canPreviousMatches(): boolean { return this.matchHistory.length > 0; }
  remove(): void {
    const signature = this.deleting(); if (!signature || this.deleteBusy()) return;
    this.deleteBusy.set(true); this.problem.set(null);
    this.subscriptions.add(this.api.deleteSignature(signature.id).subscribe({
      next: () => {
        this.deleteBusy.set(false); this.deleting.set(null);
        this.api.signatureChanges.next({ signature, removed: true });
      },
      error: failure => { this.problem.set(failure.error?.message || 'The signature could not be removed.'); this.deleteBusy.set(false); }
    }));
  }
  private load(): void {
    this.listRequest?.unsubscribe(); this.loading.set(true); this.problem.set(null);
    this.listRequest = this.api.signatures(this.cursor).subscribe({
      next: page => {
        if (!page.items.length && this.cursor) { this.cursor = null; this.history = []; this.load(); return; }
        this.entries.set(page); this.loading.set(false);
      },
      error: failure => { this.problem.set(failure.error?.message || 'The signature store could not be loaded.'); this.loading.set(false); }
    });
  }
  private loadMatches(): void {
    const signature = this.selected(); if (!signature) return;
    this.matchRequest?.unsubscribe(); this.matchLoading.set(true); this.matchProblem.set(null);
    this.matchRequest = this.api.signatureMatches(signature.id, this.matchCursor).subscribe({
      next: page => { this.matches.set(page); this.matchLoading.set(false); },
      error: failure => { this.matches.set(null); this.matchProblem.set(failure.error?.message || 'Matches could not be loaded.'); this.matchLoading.set(false); }
    });
  }
}
