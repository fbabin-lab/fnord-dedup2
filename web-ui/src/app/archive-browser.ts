import { Component, OnChanges, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { Entry, Page } from './inventory.models';
import { ArchiveEntry, ArchiveOccurrence, ArchivePage, ArchiveRoute, archiveHref, occurrenceHref } from './archive.models';
import { CandidateBadgesComponent, CandidateLegendComponent, applySignatureChange } from './candidate-badges';
import { SignatureEditorComponent } from './signature-editor';
import { formatBytes } from './file-values';

@Component({
  selector:'app-archive-browser',standalone:true,
  imports:[FormsModule,CandidateBadgesComponent,CandidateLegendComponent,SignatureEditorComponent],
  templateUrl:'./archive-browser.html',styleUrl:'./archive-explorer.css'
})
export class ArchiveBrowserComponent implements OnChanges,OnInit,OnDestroy {
  private readonly api=inject(InventoryApi);
  private request?:Subscription;
  private detailRequest?:Subscription;
  private occurrenceRequest?:Subscription;
  private signatureChanges?:Subscription;
  private cursor:string|null=null;
  private history:(string|null)[]=[];
  private occurrenceCursor:string|null=null;
  private occurrenceHistory:(string|null)[]=[];
  readonly route=input.required<ArchiveRoute>();
  readonly entryOpen=output<Entry>();
  readonly listing=signal<ArchivePage|null>(null);
  readonly detail=signal<ArchiveEntry|null>(null);
  readonly signatureEntry=signal<ArchiveEntry|null>(null);
  readonly occurrences=signal<Page<ArchiveOccurrence>|null>(null);
  readonly loading=signal(false);
  readonly detailLoading=signal(false);
  readonly occurrenceLoading=signal(false);
  readonly problem=signal<string|null>(null);
  readonly detailProblem=signal<string|null>(null);
  readonly occurrenceProblem=signal<string|null>(null);
  readonly formatBytes=formatBytes;
  readonly occurrenceHref=occurrenceHref;
  search='';
  private appliedSearch='';
  pageSize=100;
  domain:'FILESYSTEM'|'ARCHIVE_MEMBER'='ARCHIVE_MEMBER';

  ngOnChanges():void {
    this.search='';this.appliedSearch='';this.history=[];this.cursor=null;
    this.closeOccurrences();this.detail.set(null);this.signatureEntry.set(null);
    this.load(null);this.loadDetail();
  }
  ngOnInit():void {
    this.signatureChanges=this.api.signatureChanges.subscribe(change=>{
      this.listing.update(page=>page?{...page,items:page.items.map(item=>applySignatureChange(item,change))}:null);
      this.detail.update(item=>item?applySignatureChange(item,change):null);
      this.occurrences.update(page=>page?{...page,items:page.items.map(item=>applySignatureChange(item,change))}:null);
    });
  }
  ngOnDestroy():void {
    this.request?.unsubscribe();this.detailRequest?.unsubscribe();this.occurrenceRequest?.unsubscribe();this.signatureChanges?.unsubscribe();
  }
  refresh():void {this.history=[];this.load(null);this.loadDetail();this.closeOccurrences();}
  searchMembers():void {this.appliedSearch=this.search;this.history=[];this.load(null);}
  directoryHref(path:string,chain=this.route().chain):string {return archiveHref({...this.route(),chain},path);}
  memberHref(item:ArchiveEntry):string {return archiveHref(this.route(),this.route().path,item.ordinal);}
  nestedHref(item:ArchiveEntry):string {
    const chain=[this.route().chain,String(item.nestedArchive!.ordinal)].filter(Boolean).join('.');
    return archiveHref({...this.route(),chain});
  }
  pathCrumbs():{name:string;path:string}[] {
    const parts=this.route().path.split('/').filter(Boolean);
    return parts.map((name,index)=>({name,path:parts.slice(0,index+1).join('/')}));
  }
  modified(item:Entry):string {
    return item.modifiedSec==null||item.modifiedNano==null?'Unknown':
      new Date(item.modifiedSec*1000+Math.floor(item.modifiedNano/1_000_000)).toLocaleString();
  }
  next():void {const next=this.listing()?.page.nextCursor;if(next){this.history.push(this.cursor);this.load(next);}}
  previous():void {if(this.history.length)this.load(this.history.pop()??null);}
  canPrevious():boolean {return this.history.length>0;}
  private load(cursor:string|null):void {
    this.request?.unsubscribe();this.cursor=cursor;this.loading.set(true);this.problem.set(null);this.listing.set(null);
    this.request=this.api.archiveChildren(this.route(),this.route().path,this.appliedSearch,cursor,this.pageSize).subscribe({
      next:page=>{this.listing.set(page);this.loading.set(false);},
      error:failure=>{this.problem.set(failure.error?.message||'Archive metadata could not be loaded.');this.loading.set(false);}
    });
  }
  private loadDetail():void {
    this.detailRequest?.unsubscribe();this.detail.set(null);this.detailProblem.set(null);this.detailLoading.set(false);
    if(!this.route().ordinal)return;
    this.detailLoading.set(true);
    this.detailRequest=this.api.archiveMember(this.route()).subscribe({
      next:item=>{this.detail.set(item);this.detailLoading.set(false);},
      error:failure=>{this.detailProblem.set(failure.error?.message||'Member metadata could not be loaded.');this.detailLoading.set(false);}
    });
  }
  findOccurrences(domain:'FILESYSTEM'|'ARCHIVE_MEMBER'):void {
    this.domain=domain;this.occurrenceHistory=[];this.loadOccurrences(null);
  }
  closeOccurrences():void {
    this.occurrenceRequest?.unsubscribe();this.occurrences.set(null);this.occurrenceProblem.set(null);this.occurrenceLoading.set(false);
  }
  nextOccurrences():void {
    const next=this.occurrences()?.page.nextCursor;
    if(next){this.occurrenceHistory.push(this.occurrenceCursor);this.loadOccurrences(next);}
  }
  previousOccurrences():void {if(this.occurrenceHistory.length)this.loadOccurrences(this.occurrenceHistory.pop()??null);}
  canPreviousOccurrences():boolean{return this.occurrenceHistory.length>0;}
  private loadOccurrences(cursor:string|null):void {
    const item=this.detail();if(!item?.archiveMember||!item.sha256)return;
    this.occurrenceRequest?.unsubscribe();this.occurrenceCursor=cursor;
    this.occurrenceLoading.set(true);this.occurrenceProblem.set(null);this.occurrences.set(null);
    this.occurrenceRequest=this.api.archiveMemberOccurrences(item.archiveMember,this.domain,cursor).subscribe({
      next:page=>{this.occurrences.set(page);this.occurrenceLoading.set(false);},
      error:failure=>{this.occurrenceProblem.set(failure.error?.message||'Occurrences could not be loaded.');this.occurrenceLoading.set(false);}
    });
  }
}
