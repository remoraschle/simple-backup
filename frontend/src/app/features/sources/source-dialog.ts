import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CredentialsService } from '../../core/api/credentials.service';
import { SourceType } from '../../core/api/models';

/**
 * Anlegen einer Quelle.
 *
 * <p>Angeboten wird nur, was das Backend auch ausführen kann. Ein Formular für etwas, das
 * beim ersten Lauf scheitert, wäre irreführend — und fiele erst nachts um drei auf.
 */
@Component({
  selector: 'sb-source-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  templateUrl: './source-dialog.html',
})
export class SourceDialog {
  private readonly dialogRef = inject(MatDialogRef<SourceDialog>);
  private readonly credentials = inject(CredentialsService);

  private readonly allCredentials = toSignal(this.credentials.list(), { initialValue: [] });

  protected readonly passwords = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'PASSWORD'),
  );

  protected readonly tokens = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'API_TOKEN'),
  );

  /** Die Hauptversionen, für die es ein Image mit passendem pg_dump gibt. */
  protected readonly postgresVersions = [18, 17, 16, 15, 14, 13];

  protected readonly types: { value: SourceType; label: string }[] = [
    { value: 'LOCAL_PATH', label: 'Verzeichnis oder Netzlaufwerk' },
    { value: 'POSTGRES', label: 'PostgreSQL-Datenbank' },
    { value: 'GITHUB', label: 'GitHub-Repositories' },
  ];

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: [''],
    type: ['LOCAL_PATH' as SourceType, Validators.required],

    // Verzeichnis
    paths: [''],
    excludes: [''],
    oneFileSystem: [false],

    // PostgreSQL
    host: ['localhost'],
    port: [5432],
    majorVersion: [18],
    databases: [''],
    username: ['postgres'],
    passwordCredentialId: [''],
    includeGlobals: [true],

    // GitHub
    owner: [''],
    repositories: [''],
    includeForks: [false],
    includeMetadata: [true],
    tokenCredentialId: [''],
  });

  /** Ob die Angaben zum gewählten Typ vollständig sind. */
  protected complete(): boolean {
    const value = this.form.getRawValue();

    switch (value.type) {
      case 'LOCAL_PATH':
        return splitLines(value.paths).length > 0;
      case 'POSTGRES':
        return Boolean(value.host && value.username && value.passwordCredentialId);
      default:
        return Boolean(value.owner && value.tokenCredentialId);
    }
  }

  protected save(): void {
    if (this.form.invalid || !this.complete()) {
      return;
    }
    const value = this.form.getRawValue();

    this.dialogRef.close({
      name: value.name,
      description: value.description || null,
      config: this.configFor(value),
    });
  }

  private configFor(value: ReturnType<typeof this.form.getRawValue>): unknown {
    switch (value.type) {
      case 'LOCAL_PATH':
        return {
          type: 'LOCAL_PATH',
          paths: splitLines(value.paths),
          excludes: splitLines(value.excludes),
          oneFileSystem: value.oneFileSystem,
        };
      case 'POSTGRES':
        return {
          type: 'POSTGRES',
          host: value.host,
          port: value.port,
          majorVersion: value.majorVersion,
          databases: splitLines(value.databases),
          username: value.username,
          credentialId: value.passwordCredentialId,
          includeGlobals: value.includeGlobals,
        };
      default:
        return {
          type: 'GITHUB',
          owner: value.owner,
          repositories: splitLines(value.repositories),
          includeForks: value.includeForks,
          includeMetadata: value.includeMetadata,
          credentialId: value.tokenCredentialId,
        };
    }
  }
}

/** Eine Angabe je Zeile — einfacher einzugeben als eine Liste mit Plus-Knopf. */
function splitLines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}
