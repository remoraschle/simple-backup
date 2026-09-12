import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { formatBytes, formatDuration } from '../../core/api/format';
import { ProgressEvent, RunDetail as RunDetailModel, RunStatus } from '../../core/api/models';
import { RunsService } from '../../core/api/runs.service';
import { PageHeader } from '../../shared/page-header';
import { RunStatusChip } from '../../shared/run-status-chip';

/** Wie viele Zeilen die Ansicht höchstens vorhält. */
const MAX_VISIBLE_LINES = 5000;

/**
 * Ein einzelner Lauf mit seinem Protokoll.
 *
 * <p>Läuft die Sicherung noch, kommen Ausgabe und Fortschritt als Server-Sent Events
 * herein — bei einem vierstündigen Backup ist eine Ausgabe, die erst am Ende erscheint,
 * wertlos. Ist der Lauf beendet, wird das Protokoll als Ganzes geladen.
 */
@Component({
  selector: 'sb-run-detail',
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    PageHeader,
    RunStatusChip,
  ],
  templateUrl: './run-detail.html',
})
export class RunDetail {
  /** Kommt aus der Route, gebunden über withComponentInputBinding. */
  readonly id = input.required<string>();

  private readonly runs = inject(RunsService);
  private readonly logContainer = viewChild<ElementRef<HTMLElement>>('logContainer');

  protected readonly detail = signal<RunDetailModel | null>(null);
  protected readonly logLines = signal<string[]>([]);
  protected readonly progress = signal<ProgressEvent | null>(null);
  protected readonly status = signal<RunStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly follow = signal(true);

  protected readonly isRunning = computed(
    () => this.status() === 'RUNNING' || this.status() === 'QUEUED',
  );

  protected readonly bytes = formatBytes;
  protected readonly duration = formatDuration;

  constructor() {
    effect((onCleanup) => {
      const runId = this.id();
      this.load(runId);

      // Nur an laufenden Sicherungen hängen: Für einen beendeten Lauf gibt es nichts mehr
      // zu streamen, und der Server schlösse den Strom sofort wieder.
      const subscription = this.runs.stream(runId).subscribe((event) => {
        switch (event.kind) {
          case 'log':
            this.appendLine(event.payload.line);
            break;
          case 'progress':
            this.progress.set(event.payload);
            break;
          case 'step':
            // Der Schritt hat sich geändert; die Übersicht neu laden.
            this.runs.get(runId).subscribe((detail) => this.detail.set(detail));
            break;
          case 'finished':
            this.status.set(event.payload.status);
            this.progress.set(null);
            this.runs.get(runId).subscribe((detail) => this.detail.set(detail));
            break;
        }
      });

      onCleanup(() => subscription.unsubscribe());
    });
  }

  private load(runId: string): void {
    this.loading.set(true);
    this.runs.get(runId).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.status.set(detail.summary.status);
        this.loading.set(false);

        // Ein beendeter Lauf hat sein vollständiges Protokoll bereits auf der Platte.
        if (detail.summary.status !== 'RUNNING' && detail.summary.status !== 'QUEUED') {
          this.runs
            .readLog(runId)
            .subscribe((text) => this.logLines.set(text.split('\n').slice(-MAX_VISIBLE_LINES)));
        }
      },
      error: () => this.loading.set(false),
    });
  }

  /**
   * Hängt eine Zeile an und begrenzt die Anzahl.
   *
   * <p>Ein Lauf über viele Dateien erzeugt Zehntausende Zeilen; alle im Browser zu halten
   * würde die Ansicht unbenutzbar machen. Das vollständige Protokoll bleibt auf dem Server.
   */
  private appendLine(line: string): void {
    this.logLines.update((lines) => {
      const next = [...lines, line];
      return next.length > MAX_VISIBLE_LINES ? next.slice(-MAX_VISIBLE_LINES) : next;
    });

    if (this.follow()) {
      queueMicrotask(() => {
        const container = this.logContainer()?.nativeElement;
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });
    }
  }

  protected cancel(): void {
    this.runs.cancel(this.id()).subscribe();
  }
}
