import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CatalogService } from '../../core/api/catalog.service';
import { formatBytes, formatRelative } from '../../core/api/format';
import { Restore, Snapshot } from '../../core/api/models';
import { SnapshotsService } from '../../core/api/snapshots.service';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { RestoreDialog } from './restore-dialog';
import { TreeNode, flatten, toNode } from './tree-node';

/**
 * Wiederherstellung.
 *
 * <p>Die Seite, für die es das ganze Werkzeug gibt. Sie ist deshalb bewusst geradlinig:
 * Ziel wählen, Stand wählen, hineinschauen, zurückholen. Wer hier steht, hat meist gerade
 * etwas verloren und wenig Geduld für Umwege.
 */
@Component({
  selector: 'sb-restore',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
  ],
  templateUrl: './restore.html',
})
export class RestorePage {
  private readonly catalog = inject(CatalogService);
  private readonly snapshotsService = inject(SnapshotsService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly targets = toSignal(this.catalog.listTargets(), { initialValue: [] });

  protected readonly selectedTargetId = signal<string | null>(null);
  protected readonly snapshots = signal<Snapshot[]>([]);
  protected readonly selectedSnapshot = signal<Snapshot | null>(null);

  protected readonly roots = signal<TreeNode[]>([]);
  protected readonly selectedPaths = signal<Set<string>>(new Set());
  protected readonly loadingTree = signal(false);
  protected readonly busy = signal(false);

  protected readonly activeRestore = signal<Restore | null>(null);
  protected readonly restoreLog = signal<string[]>([]);

  protected readonly rows = computed(() => flatten(this.roots()));
  protected readonly bytes = formatBytes;
  protected readonly relative = formatRelative;

  protected targetChanged(targetId: string): void {
    this.selectedTargetId.set(targetId);
    this.selectedSnapshot.set(null);
    this.roots.set([]);
    this.selectedPaths.set(new Set());

    this.snapshotsService.list(targetId).subscribe((snapshots) => this.snapshots.set(snapshots));
  }

  /** Fragt das Repository selbst — die Datenbank ist nur eine Abschrift. */
  protected refresh(): void {
    const targetId = this.selectedTargetId();
    if (!targetId) {
      return;
    }
    this.busy.set(true);
    this.snapshotsService.refresh(targetId).subscribe({
      next: (snapshots) => {
        this.snapshots.set(snapshots);
        this.busy.set(false);
        this.snackBar.open(`${snapshots.length} Stände im Repository`, 'OK', { duration: 4000 });
      },
      error: () => this.busy.set(false),
    });
  }

  protected chooseSnapshot(snapshot: Snapshot): void {
    this.selectedSnapshot.set(snapshot);
    this.selectedPaths.set(new Set());
    this.loadingTree.set(true);

    this.snapshotsService.browse(snapshot.id).subscribe({
      next: (result) => {
        this.roots.set(result.entries.map((entry) => toNode(entry, 0)));
        this.loadingTree.set(false);
      },
      error: () => this.loadingTree.set(false),
    });
  }

  protected toggle(node: TreeNode): void {
    if (!node.directory) {
      return;
    }
    if (node.children) {
      node.expanded = !node.expanded;
      this.roots.update((nodes) => [...nodes]);
      return;
    }

    const snapshot = this.selectedSnapshot();
    if (!snapshot) {
      return;
    }
    node.loading = true;
    this.roots.update((nodes) => [...nodes]);

    this.snapshotsService.browse(snapshot.id, node.path).subscribe({
      next: (result) => {
        node.children = result.entries.map((entry) => toNode(entry, node.level + 1));
        node.expanded = true;
        node.loading = false;
        this.roots.update((nodes) => [...nodes]);
      },
      error: () => {
        node.loading = false;
        this.roots.update((nodes) => [...nodes]);
      },
    });
  }

  protected isSelected(node: TreeNode): boolean {
    return this.selectedPaths().has(node.path);
  }

  protected select(node: TreeNode, checked: boolean): void {
    this.selectedPaths.update((paths) => {
      const next = new Set(paths);
      if (checked) {
        next.add(node.path);
      } else {
        next.delete(node.path);
      }
      return next;
    });
  }

  protected downloadUrl(node: TreeNode): string {
    const snapshot = this.selectedSnapshot();
    return snapshot ? this.snapshotsService.fileUrl(snapshot.id, node.path) : '';
  }

  /**
   * Startet die Wiederherstellung.
   *
   * <p>Ohne Auswahl wird der ganze Stand zurückgeholt — das ist der Fall, um den es im
   * Ernstfall geht, und er soll nicht am Ankreuzen von tausend Zeilen hängen.
   */
  protected startRestore(): void {
    const snapshot = this.selectedSnapshot();
    if (!snapshot) {
      return;
    }
    const includes = [...this.selectedPaths()];

    this.dialog
      .open(RestoreDialog, { data: { count: includes.length } })
      .afterClosed()
      .subscribe((targetPath?: string) => {
        if (!targetPath) {
          return;
        }
        this.snapshotsService
          .startRestore({ snapshotId: snapshot.id, targetPath, includes })
          .subscribe({
            next: (restore) => {
              this.activeRestore.set(restore);
              this.restoreLog.set([]);
              this.followRestore(restore.id);
            },
            error: () => undefined,
          });
      });
  }

  /**
   * Fragt den Zustand der Wiederherstellung, bis sie fertig ist.
   *
   * <p>Kurzes Nachfragen statt eines Datenstroms: Eine Wiederherstellung schreibt wenige
   * Zeilen, und jemand sitzt davor und sieht zu.
   */
  private followRestore(restoreId: string): void {
    const timer = setInterval(() => {
      this.snapshotsService.restoreLog(restoreId).subscribe({
        next: (log) => this.restoreLog.set(log.lines),
        error: () => undefined,
      });

      this.snapshotsService.restore(restoreId).subscribe({
        next: (restore) => {
          this.activeRestore.set(restore);
          if (restore.state !== 'RUNNING') {
            clearInterval(timer);
            this.snackBar.open(
              restore.state === 'SUCCEEDED'
                ? 'Wiederherstellung abgeschlossen'
                : 'Wiederherstellung fehlgeschlagen',
              'OK',
              { duration: 8000 },
            );
          }
        },
        error: () => clearInterval(timer),
      });
    }, 2000);
  }

  protected check(): void {
    const targetId = this.selectedTargetId();
    if (!targetId) {
      return;
    }
    this.busy.set(true);
    this.snapshotsService.check(targetId, 5).subscribe({
      next: (outcome) => {
        this.busy.set(false);
        this.snackBar.open(outcome.message, 'OK', { duration: 10000 });
      },
      error: () => this.busy.set(false),
    });
  }

  protected verify(): void {
    const targetId = this.selectedTargetId();
    if (!targetId) {
      return;
    }
    this.busy.set(true);
    this.snapshotsService.verify(targetId).subscribe({
      next: (outcome) => {
        this.busy.set(false);
        this.snackBar.open(outcome.message, 'OK', { duration: 10000 });
      },
      error: () => this.busy.set(false),
    });
  }
}
