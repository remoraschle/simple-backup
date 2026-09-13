import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CredentialsService } from '../../core/api/credentials.service';
import { ChannelType, NotificationChannel, OutboxEntry, Severity } from '../../core/api/models';
import { NotificationsService } from '../../core/api/notifications.service';
import { formatRelative } from '../../core/api/format';
import { EmptyState } from '../../shared/empty-state';

/**
 * Kanäle für Benachrichtigungen und der Blick in den Postausgang.
 *
 * <p>Der Postausgang steht hier nicht zur Zierde: Er beantwortet die einzige Frage, die im
 * Ernstfall zählt — kam die Meldung an, und wenn nicht, warum nicht.
 */
@Component({
  selector: 'sb-channels-panel',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
  ],
  templateUrl: './channels-panel.html',
})
export class ChannelsPanel {
  private readonly notifications = inject(NotificationsService);
  private readonly credentials = inject(CredentialsService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly channels = signal<NotificationChannel[]>([]);
  protected readonly outbox = signal<OutboxEntry[]>([]);
  protected readonly columns = ['name', 'type', 'severity', 'actions'];
  protected readonly outboxColumns = ['status', 'title', 'created'];

  protected readonly relative = formatRelative;

  private readonly allCredentials = toSignal(this.credentials.list(), { initialValue: [] });

  /** Für Pushover kommt nur ein Zugang dieser Art infrage. */
  protected readonly pushoverCredentials = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'PUSHOVER'),
  );

  protected readonly headerCredentials = computed(() =>
    this.allCredentials().filter(
      (credential) => credential.type === 'API_TOKEN' || credential.type === 'PASSWORD',
    ),
  );

  protected readonly severities: { value: Severity; label: string }[] = [
    { value: 'INFO', label: 'Alles, auch Erfolgsmeldungen' },
    { value: 'WARNING', label: 'Ab Teilerfolg' },
    { value: 'CRITICAL', label: 'Nur Kritisches' },
  ];

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    type: ['PUSHOVER' as ChannelType, Validators.required],
    minSeverity: ['WARNING' as Severity, Validators.required],
    enabled: [true],
    // Pushover
    credentialId: [''],
    device: [''],
    emergency: [true],
    // Webhook
    url: [''],
    headerName: [''],
    headerCredentialId: [''],
  });

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.notifications.listChannels().subscribe((channels) => this.channels.set(channels));
    this.notifications.outbox(10).subscribe((page) => this.outbox.set(page.content));
  }

  protected describe(channel: NotificationChannel): string {
    return channel.config.type === 'PUSHOVER'
      ? channel.config.emergency
        ? 'Pushover, mit Quittierungspflicht'
        : 'Pushover'
      : channel.config.url;
  }

  protected severityLabel(severity: Severity): string {
    return this.severities.find((entry) => entry.value === severity)?.label ?? severity;
  }

  protected save(): void {
    const value = this.form.getRawValue();
    const config =
      value.type === 'PUSHOVER'
        ? {
            type: 'PUSHOVER',
            credentialId: value.credentialId,
            device: value.device || null,
            emergency: value.emergency,
            retrySeconds: null,
            expireSeconds: null,
          }
        : {
            type: 'WEBHOOK',
            url: value.url,
            headerName: value.headerName || null,
            credentialId: value.headerName ? value.headerCredentialId || null : null,
          };

    if (this.form.invalid || !this.configComplete(value.type, config)) {
      return;
    }

    this.notifications
      .createChannel({
        name: value.name,
        config,
        enabled: value.enabled,
        minSeverity: value.minSeverity,
      })
      .subscribe({
        next: () => {
          this.snackBar.open('Kanal angelegt', 'OK', { duration: 4000 });
          this.form.reset({
            type: value.type,
            minSeverity: 'WARNING',
            enabled: true,
            emergency: true,
          });
          this.reload();
        },
        error: () => undefined,
      });
  }

  private configComplete(type: ChannelType, config: Record<string, unknown>): boolean {
    return type === 'PUSHOVER' ? Boolean(config['credentialId']) : Boolean(config['url']);
  }

  protected test(channel: NotificationChannel): void {
    this.notifications.testChannel(channel.id).subscribe({
      next: () => {
        this.snackBar.open(
          'Probemeldung abgelegt. Sie wird wie ein echter Alarm zugestellt.',
          'OK',
          { duration: 6000 },
        );
        // Kurz warten: Der Postausgang wird im Hintergrund geleert.
        setTimeout(() => this.reload(), 1500);
      },
      error: () => undefined,
    });
  }

  protected remove(channel: NotificationChannel): void {
    if (!confirm(`Kanal "${channel.name}" wirklich löschen?`)) {
      return;
    }
    this.notifications.deleteChannel(channel.id).subscribe({
      next: () => this.reload(),
      error: () => undefined,
    });
  }
}
