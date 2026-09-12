import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CatalogService } from '../../core/api/catalog.service';
import { formatBytes } from '../../core/api/format';
import { Target } from '../../core/api/models';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { TargetDialog } from './target-dialog';

@Component({
  selector: 'sb-targets',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
  ],
  templateUrl: './targets.html',
})
export class Targets {
  private readonly catalog = inject(CatalogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly targets = signal<Target[]>([]);
  protected readonly loading = signal(true);
  protected readonly columns = ['name', 'location', 'mode', 'capacity', 'actions'];
  protected readonly bytes = formatBytes;

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.catalog.listTargets().subscribe({
      next: (targets) => {
        this.targets.set(targets);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected create(): void {
    this.dialog
      .open(TargetDialog)
      .afterClosed()
      .subscribe((request) => {
        if (!request) {
          return;
        }
        this.catalog.createTarget(request).subscribe({
          next: () => {
            this.snackBar.open('Ziel angelegt', 'OK', { duration: 4000 });
            this.reload();
          },
          error: () => undefined,
        });
      });
  }

  protected remove(target: Target): void {
    if (
      !confirm(
        `Ziel "${target.name}" wirklich löschen?\n\nDie bereits gesicherten Daten am Ziel bleiben erhalten.`,
      )
    ) {
      return;
    }
    this.catalog.deleteTarget(target.id).subscribe({
      next: () => {
        this.snackBar.open('Ziel gelöscht', 'OK', { duration: 4000 });
        this.reload();
      },
      error: () => undefined,
    });
  }

  /** Wohin geschrieben wird, in einer Zeile. */
  protected locationOf(target: Target): string {
    return target.config.type === 'LOCAL_PATH'
      ? target.config.path
      : `${target.config.endpoint}/${target.config.bucket}${target.config.prefix ? '/' + target.config.prefix : ''}`;
  }

  protected usedPercent(target: Target): number | null {
    const capacity = target.capacity;
    if (!capacity?.totalBytes || !capacity.usedBytes) {
      return null;
    }
    return Math.round((capacity.usedBytes / capacity.totalBytes) * 100);
  }
}
