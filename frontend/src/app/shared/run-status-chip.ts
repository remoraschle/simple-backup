import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RunStatus } from '../core/api/models';

interface StatusAppearance {
  readonly icon: string;
  readonly label: string;
  readonly classes: string;
  readonly hint: string;
}

/**
 * Zeigt den Zustand eines Laufs.
 *
 * <p>Farbe trägt die Aussage nie allein: Jeder Zustand hat zusätzlich ein eigenes Symbol und
 * einen Text. Wer Farben schlecht unterscheidet, soll die Übersicht trotzdem lesen können —
 * und gerade hier zählt der Unterschied zwischen „lief durch" und „lief halb durch".
 */
@Component({
  selector: 'sb-run-status-chip',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    <span
      class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
      [class]="appearance().classes"
      [matTooltip]="appearance().hint"
    >
      <mat-icon class="!h-4 !w-4 !text-base leading-4">{{ appearance().icon }}</mat-icon>
      {{ appearance().label }}
    </span>
  `,
})
export class RunStatusChip {
  readonly status = input.required<RunStatus | string | null>();

  protected readonly appearance = computed<StatusAppearance>(() => {
    switch (this.status()) {
      case 'SUCCESS':
        return {
          icon: 'check_circle',
          label: 'Erfolgreich',
          classes: 'bg-status-success/15 text-status-success',
          hint: 'Alle Ziele wurden bedient',
        };
      case 'PARTIAL':
        return {
          icon: 'error_outline',
          label: 'Teilerfolg',
          classes: 'bg-status-partial/15 text-status-partial',
          hint: 'Mindestens ein Ziel wurde bedient, mindestens eines nicht',
        };
      case 'FAILED':
        return {
          icon: 'cancel',
          label: 'Fehlgeschlagen',
          classes: 'bg-status-failed/15 text-status-failed',
          hint: 'Es wurde nichts gesichert',
        };
      case 'RUNNING':
        return {
          icon: 'sync',
          label: 'Läuft',
          classes: 'bg-status-running/15 text-status-running',
          hint: 'Die Sicherung läuft gerade',
        };
      case 'QUEUED':
        return {
          icon: 'schedule',
          label: 'Wartet',
          classes: 'bg-status-idle/15 text-status-idle',
          hint: 'Wartet auf einen freien Platz',
        };
      case 'TIMEOUT':
        return {
          icon: 'timer_off',
          label: 'Zeitlimit',
          classes: 'bg-status-failed/15 text-status-failed',
          hint: 'Das Zeitlimit wurde überschritten und der Lauf abgebrochen',
        };
      case 'CANCELLED':
        return {
          icon: 'block',
          label: 'Abgebrochen',
          classes: 'bg-status-idle/15 text-status-idle',
          hint: 'Der Lauf wurde abgebrochen',
        };
      default:
        return {
          icon: 'remove',
          label: 'Noch nie gelaufen',
          classes: 'bg-status-idle/15 text-status-idle',
          hint: 'Für diesen Plan gibt es noch keinen Lauf',
        };
    }
  });
}
