import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { Entry, Signature } from './inventory.models';
import { formatBytes } from './file-values';

@Component({
  selector: 'app-signature-editor',
  standalone: true,
  imports: [FormsModule],
  template: `
    <dialog #dialog class="signature-dialog" (cancel)="cancel($event)" aria-labelledby="signature-editor-title">
      <form (ngSubmit)="save()">
        <h2 id="signature-editor-title">{{ current ? 'Edit signature' : 'Add to signature store' }}</h2>
        <p class="path-text">{{ entry()?.filename || current?.tag || 'Content signature' }}</p>
        <p>{{ formatBytes(entry()?.size || current?.size || '0') }} · SHA-256</p>
        <p class="hash-text">{{ entry()?.sha256 || current?.sha256 }}</p>
        <p>Every file with this size and hash is a removal candidate, including all duplicate copies.</p>
        <label>Tag (optional)<input name="signatureTag" [(ngModel)]="tag" maxlength="120" autofocus [disabled]="busy()"></label>
        <label>Memo (optional)<textarea name="signatureMemo" [(ngModel)]="memo" maxlength="4000" rows="4" [disabled]="busy()"></textarea></label>
        @if (problem()) { <p class="message error" role="alert">{{ problem() }}</p> }
        <div class="signature-actions">
          <button type="submit" [disabled]="busy()">{{ busy() ? 'Saving…' : 'Save signature' }}</button>
          <button type="button" class="secondary" (click)="closed.emit()" [disabled]="busy()">Cancel</button>
        </div>
      </form>
    </dialog>
  `,
  styleUrls: ['./file-search.css', './signature-store.css']
})
export class SignatureEditorComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly api = inject(InventoryApi);
  private request?: Subscription;
  @ViewChild('dialog') dialog!: ElementRef<HTMLDialogElement>;
  readonly entry = input<Entry | null>(null);
  readonly signature = input<Signature | null>(null);
  readonly closed = output<void>();
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly formatBytes = formatBytes;
  current: Signature | null = null;
  tag = '';
  memo = '';

  ngOnInit(): void {
    this.current = this.signature() || this.entry()?.signature || null;
    this.tag = this.current?.tag || '';
    this.memo = this.current?.memo || '';
  }
  ngAfterViewInit(): void { this.dialog.nativeElement.showModal(); }
  ngOnDestroy(): void { this.request?.unsubscribe(); this.dialog.nativeElement.close(); }
  cancel(event: Event): void { event.preventDefault(); if (!this.busy()) this.closed.emit(); }
  save(): void {
    if (this.busy()) return;
    const entry = this.entry();
    if (!this.current && (!entry?.scanId || !entry.sha256)) {
      this.problem.set('A recorded regular file with a saved SHA-256 is required.'); return;
    }
    this.busy.set(true); this.problem.set(null);
    const body = { tag: this.tag, memo: this.memo,
      ...(!this.current ? { scanId: entry!.scanId, entryId: entry!.entryId } : {}) };
    this.request = this.api.saveSignature(this.current?.id || null, body).subscribe({
      next: signature => {
        this.api.signatureChanges.next({ signature, removed: false }); this.busy.set(false); this.closed.emit();
      },
      error: failure => { this.problem.set(failure.error?.message || 'The signature could not be saved.'); this.busy.set(false); }
    });
  }
}
