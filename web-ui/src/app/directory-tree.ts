import { Component, EventEmitter, Input, OnDestroy, Output, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { InventoryApi } from './inventory.api';
import { Entry } from './inventory.models';
import { copy } from './copy';

interface TreeNode {
  entryId: number;
  name: string;
  depth: number;
  expanded: boolean;
  loading: boolean;
  loaded: boolean;
  nextCursor: string | null;
  children: TreeNode[];
  error: string | null;
}

@Component({
  selector: 'app-directory-tree',
  standalone: true,
  templateUrl: './directory-tree.html',
  styleUrl: './directory-tree.css'
})
export class DirectoryTreeComponent implements OnDestroy {
  readonly copy = copy;
  private readonly api = inject(InventoryApi);
  private requests = new Subscription();
  private root: TreeNode | null = null;
  readonly visible = signal<TreeNode[]>([]);
  @Output() readonly open = new EventEmitter<number>();
  @Input() activeEntryId = 1;

  private currentScanId = 0;
  @Input() set scanId(value: number) {
    if (value === this.currentScanId) return;
    this.currentScanId = value;
    this.reset();
  }
  @Input() set rootName(value: string) {
    if (this.root && value) {
      this.root.name = value;
      this.update();
    }
  }

  toggle(node: TreeNode): void {
    node.expanded = !node.expanded;
    this.update();
    if (node.expanded && !node.loaded) this.load(node);
  }

  load(node: TreeNode): void {
    if (node.loading || !this.currentScanId) return;
    node.loading = true;
    node.error = null;
    this.update();
    this.requests.add(this.api.children(this.currentScanId, node.entryId, node.nextCursor).subscribe({
      next: page => {
        node.children.push(...page.items.filter((entry: Entry) => entry.kind === 'DIRECTORY')
          .map((entry: Entry) => ({
            entryId: entry.entryId, name: entry.filename, depth: node.depth + 1,
            expanded: false, loading: false, loaded: false, nextCursor: null, children: [],
            error: null
          })));
        node.nextCursor = page.page.nextCursor;
        node.loaded = true;
        node.loading = false;
        this.update();
      },
      error: () => { node.loading = false; node.error = copy.folderLoadFailed; this.update(); }
    }));
  }

  ngOnDestroy(): void { this.requests.unsubscribe(); }

  private reset(): void {
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.root = {
      entryId: 1, name: copy.root, depth: 0, expanded: true, loading: false,
      loaded: false, nextCursor: null, children: [], error: null
    };
    this.update();
    this.load(this.root);
  }
  private update(): void {
    const nodes: TreeNode[] = [];
    const visit = (node: TreeNode): void => {
      nodes.push(node);
      if (node.expanded) node.children.forEach(visit);
    };
    if (this.root) visit(this.root);
    this.visible.set(nodes);
  }
}
