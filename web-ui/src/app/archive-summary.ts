import { Component, OnChanges, OnDestroy, inject, input, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { ArchiveSummary } from './archive.models';
import { ArchiveAccountingComponent } from './archive-accounting';

@Component({
  selector:'app-archive-summary',standalone:true,imports:[ArchiveAccountingComponent],styleUrl:'./archive-explorer.css',
  template:`<section class="archive-panel"><h2>Duplicate accounting by storage type</h2>
    <p>{{ scanId() ? 'This scan only.' : 'Across all recorded scans.' }} Includes matches between files and archive members.</p>
    @if (loading()) { <p role="status">Loading duplicate accounting…</p> }
    @if (problem()) { <p class="archive-error" role="alert">{{ problem() }}</p> }
    @if (summary(); as value) { <app-archive-accounting [value]="value" /> }
    <button type="button" (click)="load()" [disabled]="loading()">Refresh accounting</button>
  </section>`
})
export class ArchiveSummaryComponent implements OnChanges,OnDestroy {
  private readonly api=inject(InventoryApi);
  private request?:Subscription;
  readonly scanId=input<number|null>(null);
  readonly summary=signal<ArchiveSummary|null>(null);
  readonly loading=signal(false);
  readonly problem=signal<string|null>(null);
  ngOnChanges():void { this.load(); }
  ngOnDestroy():void { this.request?.unsubscribe(); }
  load():void {
    this.request?.unsubscribe();this.loading.set(true);this.problem.set(null);this.summary.set(null);
    this.request=this.api.archiveSummary(this.scanId()).subscribe({
      next:value=>{this.summary.set(value);this.loading.set(false);},
      error:failure=>{this.problem.set(failure.error?.message||'Accounting could not be loaded.');this.loading.set(false);}
    });
  }
}
