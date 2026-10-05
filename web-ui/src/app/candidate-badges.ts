import { Component, input } from '@angular/core';
import { CandidateSignals, SignatureChange } from './inventory.models';

export function applySignatureChange<T extends CandidateSignals & { size: string; sha256: string | null }>(
  value: T, change: SignatureChange
): T {
  if (value.size !== change.signature.size || value.sha256 !== change.signature.sha256 ||
      ('algorithm' in value && value.algorithm && value.algorithm !== 'SHA-256')) return value;
  return { ...value, signature: change.removed ? null : change.signature,
    signatureMatch: !change.removed, removalCandidate: !change.removed };
}

@Component({
  selector: 'app-candidate-badges',
  standalone: true,
  template: `
    @if (value().signatureMatch) {
      <span class="candidate-badge signature-badge" [title]="value().signature?.memo || 'Every matching copy is a removal candidate.'">
        Signature match · removal candidate@if (value().signature?.tag) { · {{ value().signature?.tag }} }
      </span>
    }
    @if (value().duplicateCandidate) { <span class="candidate-badge duplicate-badge">Duplicate candidate</span> }
  `
})
export class CandidateBadgesComponent { readonly value = input.required<CandidateSignals>(); }

@Component({
  selector: 'app-candidate-legend',
  standalone: true,
  template: `<p class="candidate-legend"><span class="candidate-badge signature-badge">Red: signature match</span>
    All matching copies are removal candidates.
    <span class="candidate-badge duplicate-badge">Amber: duplicate candidate</span> Review which copy to keep.</p>`
})
export class CandidateLegendComponent {}
