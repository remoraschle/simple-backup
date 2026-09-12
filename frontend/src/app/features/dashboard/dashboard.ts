import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { CatalogService } from '../../core/api/catalog.service';
import { formatRelative } from '../../core/api/format';
import { Plan } from '../../core/api/models';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { RunStatusChip } from '../../shared/run-status-chip';

/**
 * Übersicht.
 *
 * <p>Die eine Frage, die diese Seite beantworten muss: Ist alles in Ordnung? Deshalb steht
 * ganz oben eine einzige Aussage — und nicht eine Zahlenwand, aus der man sie sich
 * zusammensuchen müsste.
 */
@Component({
  selector: 'sb-dashboard',
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
    RunStatusChip,
  ],
  templateUrl: './dashboard.html',
})
export class Dashboard {
  private readonly catalog = inject(CatalogService);

  protected readonly plans = signal<Plan[]>([]);
  protected readonly loading = signal(true);

  protected readonly failing = computed(() =>
    this.plans().filter(
      (plan) => plan.lastRunStatus === 'FAILED' || plan.lastRunStatus === 'TIMEOUT',
    ),
  );

  protected readonly partial = computed(() =>
    this.plans().filter((plan) => plan.lastRunStatus === 'PARTIAL'),
  );

  /**
   * Pläne, die nie gelaufen sind, obwohl sie aktiv sind.
   *
   * <p>Der gefährlichste Zustand ist nicht das fehlgeschlagene, sondern das ausgebliebene
   * Backup — es meldet sich nie von selbst.
   */
  protected readonly neverRan = computed(() =>
    this.plans().filter((plan) => plan.enabled && !plan.lastRunAt),
  );

  protected readonly allHealthy = computed(
    () =>
      this.plans().length > 0 &&
      this.failing().length === 0 &&
      this.partial().length === 0 &&
      this.neverRan().length === 0,
  );

  protected readonly relative = formatRelative;

  constructor() {
    this.catalog.listPlans().subscribe({
      next: (plans) => {
        this.plans.set(plans);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
