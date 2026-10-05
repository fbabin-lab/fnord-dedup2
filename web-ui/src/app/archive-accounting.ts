import { Component, input } from '@angular/core';
import { ArchiveSummary } from './archive.models';
import { formatBytes } from './file-values';

@Component({
  selector: 'app-archive-accounting', standalone: true, styleUrl: './archive-explorer.css',
  template: `
    <div class="archive-metrics" aria-label="Separate filesystem and archive duplicate accounting">
      <div class="archive-metric"><span>Filesystem duplicate candidates</span>
        <strong>{{ value().filesystem.candidateFiles }}</strong>
        <span>{{ formatBytes(value().filesystem.candidateBytes) }} · observed file bytes</span></div>
      <div class="archive-metric"><span>Archive member duplicate candidates</span>
        <strong>{{ value().archive.candidateMembers }}</strong>
        <span>{{ formatBytes(value().archive.candidateLogicalBytes) }} · uncompressed logical bytes</span>
        <span>Directly removable archive-member bytes: 0 B</span></div>
    </div>
    <p class="archive-note">{{ value().semantics }} Nested containers and their contents are distinct observations;
      do not add these totals together as disk savings.</p>
    @if (value().available) {
      <p>Archive coverage: {{ value().coverage.hashedMembers }} hashed members;
        {{ value().coverage.unresolvedMembers }} unresolved;
        {{ value().coverage.partialRoots }} partial, {{ value().coverage.skippedRoots }} skipped,
        {{ value().coverage.unfinishedRoots }} unfinished root archives.</p>
    } @else { <p>No archive analysis is recorded in this database.</p> }
  `
})
export class ArchiveAccountingComponent {
  readonly value = input.required<ArchiveSummary>();
  readonly formatBytes = formatBytes;
}
