import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Credential, CredentialType, CredentialsService } from '../../core/api/credentials.service';
import { PageHeader } from '../../shared/page-header';

/**
 * Verwaltung der Zugangsdaten.
 *
 * <p>Werte lassen sich hier eintragen, aber nie wieder auslesen: Die API gibt keinen
 * Klartext heraus, und der Typ, mit dem sie antwortet, hat schon kein Feld dafür.
 */
@Component({
  selector: 'sb-settings',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
    PageHeader,
  ],
  templateUrl: './settings.html',
})
export class Settings {
  private readonly credentials = inject(CredentialsService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly items = signal<Credential[]>([]);
  protected readonly columns = ['name', 'type', 'created', 'actions'];

  protected readonly types: { value: CredentialType; label: string; hint: string }[] = [
    {
      value: 'RESTIC_REPOSITORY_PASSWORD',
      label: 'restic-Repository-Passwort',
      hint: 'Ohne dieses Passwort sind die Sicherungen unwiederbringlich verloren',
    },
    {
      value: 'S3_KEYPAIR',
      label: 'S3-Schlüsselpaar',
      hint: 'Als JSON: {"accessKeyId":"…","secretAccessKey":"…"}',
    },
    { value: 'API_TOKEN', label: 'API-Token', hint: 'Etwa ein GitHub-Token' },
    { value: 'PASSWORD', label: 'Passwort', hint: '' },
    { value: 'SSH_PRIVATE_KEY', label: 'Privater SSH-Schlüssel', hint: '' },
    { value: 'PUSHOVER', label: 'Pushover-Zugang', hint: '' },
  ];

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    type: ['RESTIC_REPOSITORY_PASSWORD' as CredentialType, Validators.required],
    description: [''],
    secret: ['', Validators.required],
  });

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.credentials.list().subscribe((items) => this.items.set(items));
  }

  protected hintFor(type: CredentialType): string {
    return this.types.find((entry) => entry.value === type)?.hint ?? '';
  }

  protected labelFor(type: string): string {
    return this.types.find((entry) => entry.value === type)?.label ?? type;
  }

  protected save(): void {
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();

    this.credentials
      .create({
        name: value.name,
        type: value.type,
        description: value.description || null,
        secret: value.secret,
      })
      .subscribe({
        next: () => {
          this.snackBar.open('Zugang gespeichert', 'OK', { duration: 4000 });
          this.form.reset({ type: value.type });
          this.reload();
        },
        error: () => undefined,
      });
  }

  protected remove(credential: Credential): void {
    if (!confirm(`Zugang "${credential.name}" wirklich löschen?`)) {
      return;
    }
    this.credentials.delete(credential.id).subscribe({
      next: () => this.reload(),
      error: () => undefined,
    });
  }
}
