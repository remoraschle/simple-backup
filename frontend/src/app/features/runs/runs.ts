import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { formatBytes, formatDuration, formatRelative } from '../../core/api/format';
import { Run } from '../../core/api/models';
import { RunsService } from '../../core/api/runs.service';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { RunStatusChip } from '../../shared/run-status-chip';

@Component({
  selector: 'sb-runs',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
    RunStatusChip,
  ],
  templateUrl: './runs.html',
})
export class Runs {
  private readonly runsService = inject(RunsService);
  private readonly route = inject(ActivatedRoute);

  protected readonly runs = signal<Run[]>([]);
  protected readonly loading = signal(true);
  protected readonly planId = signal<string | undefined>(undefined);
  protected readonly columns = ['status', 'started', 'duration', 'transferred', 'files', 'actions'];

  protected readonly bytes = formatBytes;
  protected readonly duration = formatDuration;
  protected readonly relative = formatRelative;

  constructor() {
    this.route.queryParamMap.subscribe((params) => {
      this.planId.set(params.get('planId') ?? undefined);
      this.reload();
    });
  }

  protected reload(): void {
    this.loading.set(true);
    this.runsService.list(this.planId()).subscribe({
      next: (page) => {
        this.runs.set(page.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
