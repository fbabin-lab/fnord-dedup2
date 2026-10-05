import { Component, OnChanges, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { Entry, Page } from './inventory.models';
import { ArchiveGroup, ArchiveGroupPage, ArchiveOccurrence, occurrenceHref } from './archive.models';
import { ArchiveAccountingComponent } from './archive-accounting';
import { CandidateBadgesComponent, applySignatureChange } from './candidate-badges';
import { formatBytes } from './file-values';

@Component({
  selector:'app-archive-analysis',standalone:true,imports:[ArchiveAccountingComponent,CandidateBadgesComponent],
  templateUrl:'./archive-analysis.html',styleUrl:'./archive-explorer.css'
})
export class ArchiveAnalysisComponent implements OnChanges,OnInit,OnDestroy {
  private readonly api=inject(InventoryApi);
  private request?:Subscription;
  private occurrenceRequest?:Subscription;
  private signatureChanges?:Subscription;
  private cursor:string|null=null;
  private history:(string|null)[]=[];
  private occurrenceCursor:string|null=null;
  private occurrenceHistory:(string|null)[]=[];
  readonly scanIds=input.required<number[]>();
  readonly entryOpen=output<Entry>();
  readonly results=signal<ArchiveGroupPage|null>(null);
  readonly selected=signal<ArchiveGroup|null>(null);
  readonly occurrences=signal<Page<ArchiveOccurrence>|null>(null);
  readonly problem=signal<string|null>(null);
  readonly occurrenceProblem=signal<string|null>(null);
  readonly loading=signal(false);
  readonly occurrenceLoading=signal(false);
  readonly formatBytes=formatBytes;
  readonly occurrenceHref=occurrenceHref;
  domain:'FILESYSTEM'|'ARCHIVE_MEMBER'='ARCHIVE_MEMBER';
  ngOnChanges():void {this.history=[];this.close();this.load(null);}
  ngOnInit():void {
    this.signatureChanges=this.api.signatureChanges.subscribe(change=>{
      this.results.update(page=>page?{...page,items:page.items.map(item=>applySignatureChange(item,change))}:null);
      this.selected.update(item=>item?applySignatureChange(item,change):null);
      this.occurrences.update(page=>page?{...page,items:page.items.map(item=>applySignatureChange(item,change))}:null);
    });
  }
  ngOnDestroy():void {this.request?.unsubscribe();this.occurrenceRequest?.unsubscribe();this.signatureChanges?.unsubscribe();}
  refresh():void {this.history=[];this.close();this.load(null);}
  next():void {const next=this.results()?.page.nextCursor;if(next){this.history.push(this.cursor);this.load(next);}}
  previous():void {if(this.history.length)this.load(this.history.pop()??null);}
  canPrevious():boolean{return this.history.length>0;}
  open(group:ArchiveGroup):void {this.selected.set(group);this.findOccurrences('ARCHIVE_MEMBER');}
  close():void {this.occurrenceRequest?.unsubscribe();this.selected.set(null);this.occurrences.set(null);this.occurrenceLoading.set(false);this.occurrenceProblem.set(null);}
  findOccurrences(domain:'FILESYSTEM'|'ARCHIVE_MEMBER'):void {this.domain=domain;this.occurrenceHistory=[];this.loadOccurrences(null);}
  nextOccurrences():void {const next=this.occurrences()?.page.nextCursor;if(next){this.occurrenceHistory.push(this.occurrenceCursor);this.loadOccurrences(next);}}
  previousOccurrences():void {if(this.occurrenceHistory.length)this.loadOccurrences(this.occurrenceHistory.pop()??null);}
  canPreviousOccurrences():boolean{return this.occurrenceHistory.length>0;}
  private load(cursor:string|null):void {
    this.request?.unsubscribe();this.cursor=cursor;this.results.set(null);this.problem.set(null);this.close();
    if(!this.scanIds().length){this.loading.set(false);return;}
    this.loading.set(true);
    this.request=this.api.archiveGroups(this.scanIds(),cursor).subscribe({
      next:page=>{this.results.set(page);this.loading.set(false);},
      error:failure=>{this.problem.set(failure.error?.message||'Archive duplicate analysis failed.');this.loading.set(false);}
    });
  }
  private loadOccurrences(cursor:string|null):void {
    const group=this.selected();if(!group)return;
    this.occurrenceRequest?.unsubscribe();this.occurrenceCursor=cursor;
    this.occurrences.set(null);this.occurrenceProblem.set(null);this.occurrenceLoading.set(true);
    this.occurrenceRequest=this.api.archiveOccurrences(this.scanIds(),group.size,group.sha256,this.domain,cursor).subscribe({
      next:page=>{this.occurrences.set(page);this.occurrenceLoading.set(false);},
      error:failure=>{this.occurrenceProblem.set(failure.error?.message||'Archive occurrence lookup failed.');this.occurrenceLoading.set(false);}
    });
  }
}
