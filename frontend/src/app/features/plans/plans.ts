import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { CatalogService } from '../../core/api/catalog.service';
import { formatRelative } from '../../core/api/format';
import { Plan } from '../../core/api/models';
import { RunsService } from '../../core/api/runs.service';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { RunStatusChip } from '../../shared/run-status-chip';
import { PlanDialog } from './plan-dialog';

@Component({
  selector: 'sb-plans',
  imports: [
    RouterLink,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
    RunStatusChip,
  ],
  templateUrl: './plans.html',
})
export class Plans {
  private readonly catalog = inject(CatalogService);
  private readonly runs = inject(RunsService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  protected readonly plans = signal<Plan[]>([]);
  protected readonly loading = signal(true);
  protected readonly columns = ['name', 'source', 'targets', 'schedule', 'lastRun', 'actions'];
  protected readonly relative = formatRelative;

  constructor() {
    this.reload();
  }

  /** Die Zielnamen als eine Zeile, damit die Tabelle kompakt bleibt. */
  protected targetNames(plan: Plan): string {
    return plan.targets.map((target) => target.name).join(', ');
  }

  protected reload(): void {
    this.loading.set(true);
    this.catalog.listPlans().subscribe({
      next: (plans) => {
        this.plans.set(plans);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected create(): void {
    this.dialog
      .open(PlanDialog)
      .afterClosed()
      .subscribe((request) => {
        if (!request) {
          return;
        }
        this.catalog.createPlan(request).subscribe({
          next: () => {
            this.snackBar.open('Plan angelegt', 'OK', { duration: 4000 });
            this.reload();
          },
          error: () => undefined,
        });
      });
  }

  /** Startet den Plan sofort, unabhängig vom Zeitplan. */
  protected runNow(plan: Plan): void {
    this.runs.start(plan.id).subscribe({
      next: (response) => {
        this.snackBar
          .open('Sicherung gestartet', 'Ansehen', { duration: 8000 })
          .onAction()
          .subscribe(() => this.router.navigate(['/laeufe', response.runId]));
        this.reload();
      },
      error: () => undefined,
    });
  }

  protected remove(plan: Plan): void {
    if (
      !confirm(
        `Plan "${plan.name}" wirklich löschen?\n\nDie bereits gesicherten Daten bleiben erhalten.`,
      )
    ) {
      return;
    }
    this.catalog.deletePlan(plan.id).subscribe({
      next: () => {
        this.snackBar.open('Plan gelöscht', 'OK', { duration: 4000 });
        this.reload();
      },
      error: () => undefined,
    });
  }
}
