import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CatalogService } from '../../core/api/catalog.service';
import { CredentialsService } from '../../core/api/credentials.service';
import { SourceType, SourceTypeInfo } from '../../core/api/models';

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
  private readonly catalog = inject(CatalogService);

  private readonly allCredentials = toSignal(this.credentials.list(), { initialValue: [] });

  /**
   * Was das Backend hier anlegen lässt.
   *
   * <p>Vom Server und nicht aus einer Liste im Frontend: Ob sich ein Blockgerät sichern
   * lässt, hängt vom Docker-Daemon und von der Freigabe ab — das weiß nur das Backend.
   */
  private readonly typeInfo = toSignal(this.catalog.sourceTypes(), { initialValue: [] });

  protected readonly devices = toSignal(this.catalog.devices(), { initialValue: [] });

  protected readonly passwords = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'PASSWORD'),
  );

  protected readonly tokens = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'API_TOKEN'),
  );

  protected readonly s3Keys = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'S3_KEYPAIR'),
  );

  /** Nur Schlüssel: Für ein Konto, das jede Nacht unbeaufsichtigt Daten holt, ist das die richtige Wahl. */
  protected readonly sshKeys = computed(() =>
    this.allCredentials().filter((credential) => credential.type === 'SSH_PRIVATE_KEY'),
  );

  /** Die Hauptversionen, für die es ein Image mit passendem pg_dump gibt. */
  protected readonly postgresVersions = [18, 17, 16, 15, 14, 13];

  protected readonly types: { value: SourceType; label: string }[] = [
    { value: 'LOCAL_PATH', label: 'Verzeichnis oder Netzlaufwerk' },
    { value: 'POSTGRES', label: 'PostgreSQL-Datenbank' },
    { value: 'GITHUB', label: 'GitHub-Repositories' },
    { value: 'S3', label: 'S3-Bucket' },
    { value: 'SFTP', label: 'SFTP-Server' },
    { value: 'BLOCK_DEVICE', label: 'Ganzer Datenträger (Abbild)' },
  ];

  /** Solange die Auskunft nicht da ist, wird nichts gesperrt -- sonst flackert das Formular. */
  private info(type: SourceType): SourceTypeInfo | undefined {
    return this.typeInfo().find((entry) => entry.type === type);
  }

  protected unavailable(type: SourceType): boolean {
    return this.info(type)?.available === false;
  }

  protected reasonFor(type: SourceType): string | null {
    return this.info(type)?.unavailableReason ?? null;
  }

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

    // S3
    endpoint: [''],
    bucket: [''],
    prefix: [''],
    region: ['us-east-1'],
    s3CredentialId: [''],

    // SFTP
    sftpHost: [''],
    sftpPort: [22],
    sftpUsername: [''],
    remotePath: [''],
    hostKey: [''],
    sshKeyCredentialId: [''],

    // Blockgerät
    device: [''],
    imageName: [''],
    sparse: [true],
  });

  /** Ob die Angaben zum gewählten Typ vollständig sind. */
  protected complete(): boolean {
    const value = this.form.getRawValue();

    switch (value.type) {
      case 'LOCAL_PATH':
        return splitLines(value.paths).length > 0;
      case 'POSTGRES':
        return Boolean(value.host && value.username && value.passwordCredentialId);
      case 'GITHUB':
        return Boolean(value.owner && value.tokenCredentialId);
      case 'S3':
        return Boolean(value.endpoint && value.bucket && value.s3CredentialId);
      case 'BLOCK_DEVICE':
        return Boolean(value.device);
      default:
        return Boolean(
          value.sftpHost &&
          value.sftpUsername &&
          value.remotePath &&
          value.hostKey &&
          value.sshKeyCredentialId,
        );
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
      case 'GITHUB':
        return {
          type: 'GITHUB',
          owner: value.owner,
          repositories: splitLines(value.repositories),
          includeForks: value.includeForks,
          includeMetadata: value.includeMetadata,
          credentialId: value.tokenCredentialId,
        };
      case 'S3':
        return {
          type: 'S3',
          endpoint: value.endpoint,
          bucket: value.bucket,
          prefix: value.prefix || null,
          region: value.region || null,
          credentialId: value.s3CredentialId,
        };
      case 'BLOCK_DEVICE':
        return {
          type: 'BLOCK_DEVICE',
          device: value.device,
          imageName: value.imageName || null,
          sparse: value.sparse,
        };
      default:
        return {
          type: 'SFTP',
          host: value.sftpHost,
          port: value.sftpPort,
          username: value.sftpUsername,
          path: value.remotePath,
          hostKey: value.hostKey,
          credentialId: value.sshKeyCredentialId,
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
