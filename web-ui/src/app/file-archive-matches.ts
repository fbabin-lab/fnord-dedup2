import { Component, OnChanges, OnDestroy, inject, input, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { Entry, Page } from './inventory.models';
import { ArchiveOccurrence, occurrenceHref } from './archive.models';

@Component({
  selector:'app-file-archive-matches',standalone:true,styleUrl:'./archive-explorer.css',
  template:`<section aria-label="Archive matches for this file"><h3>Archive occurrences · separate handling</h3>
    <button type="button" (click)="show()" [disabled]="loading()">Show archive matches</button>
    @if (loading()) { <p role="status">Loading archive matches…</p> }
    @if (problem()) { <p class="archive-error" role="alert">{{ problem() }}</p> }
    @if (page(); as result) {
      @for (item of result.items; track item.scanId + ':' + item.locationId + ':' + item.chain + ':' + item.memberId) {
        <p class="path-text"><a [href]="href(item)">{{ item.scanName }} · {{ item.path }} · member #{{ item.ordinal }}</a></p>
      } @empty { <p>No recorded archive matches.</p> }
      <div class="archive-pagination"><button type="button" (click)="previous()" [disabled]="loading() || !canPrevious()">Previous archive matches</button>
        <button type="button" (click)="next()" [disabled]="loading() || !result.page.hasMore">Next archive matches</button></div>
    }
  </section>`
})
export class FileArchiveMatchesComponent implements OnChanges,OnDestroy {
  private readonly api=inject(InventoryApi);
  private request?:Subscription;
  private cursor:string|null=null;
  private history:(string|null)[]=[];
  readonly entry=input.required<Entry>();
  readonly page=signal<Page<ArchiveOccurrence>|null>(null);
  readonly loading=signal(false);
  readonly problem=signal<string|null>(null);
  readonly href=occurrenceHref;
  ngOnChanges():void {this.request?.unsubscribe();this.page.set(null);this.loading.set(false);this.problem.set(null);this.history=[];}
  ngOnDestroy():void {this.request?.unsubscribe();}
  show():void {this.history=[];this.load(null);}
  next():void {const next=this.page()?.page.nextCursor;if(next){this.history.push(this.cursor);this.load(next);}}
  previous():void {if(this.history.length)this.load(this.history.pop()??null);}
  canPrevious():boolean {return this.history.length>0;}
  private load(cursor:string|null):void {
    if(!this.entry().scanId)return;
    this.request?.unsubscribe();this.cursor=cursor;this.loading.set(true);this.page.set(null);this.problem.set(null);
    this.request=this.api.fileArchiveOccurrences(this.entry().scanId!,this.entry().entryId,cursor).subscribe({
      next:page=>{this.page.set(page);this.loading.set(false);},
      error:failure=>{this.problem.set(failure.error?.message||'Archive matches could not be loaded.');this.loading.set(false);}
    });
  }
}
