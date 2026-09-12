import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { toSignal } from '@angular/core/rxjs-interop';
import { Credential, CredentialsService } from '../../core/api/credentials.service';
import { TargetMode, TargetType } from '../../core/api/models';

@Component({
  selector: 'sb-target-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './target-dialog.html',
})
export class TargetDialog {
  private readonly dialogRef = inject(MatDialogRef<TargetDialog>);
  private readonly credentials = inject(CredentialsService);

  protected readonly allCredentials = toSignal(this.credentials.list(), { initialValue: [] });

  protected readonly repositoryPasswords = computed(() =>
    this.allCredentials().filter(
      (credential: Credential) =>
        credential.type === 'RESTIC_REPOSITORY_PASSWORD' || credential.type === 'PASSWORD',
    ),
  );

  protected readonly s3Keys = computed(() =>
    this.allCredentials().filter((credential: Credential) => credential.type === 'S3_KEYPAIR'),
  );

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: [''],
    type: ['LOCAL_PATH' as TargetType, Validators.required],
    mode: ['RESTIC' as TargetMode, Validators.required],
    path: [''],
    endpoint: [''],
    bucket: [''],
    prefix: [''],
    credentialId: [''],
    repositoryPasswordCredentialId: [''],
  });

  protected readonly type = signal<TargetType>('LOCAL_PATH');
  protected readonly mode = signal<TargetMode>('RESTIC');

  constructor() {
    this.form.controls.type.valueChanges.subscribe((value) => this.type.set(value));
    this.form.controls.mode.valueChanges.subscribe((value) => this.mode.set(value));
  }

  protected save(): void {
    const value = this.form.getRawValue();

    // Im Spiegel-Modus gibt es kein Repository und damit kein Passwort.
    const passwordId =
      value.mode === 'RESTIC' ? value.repositoryPasswordCredentialId || null : null;

    const config =
      value.type === 'LOCAL_PATH'
        ? { type: 'LOCAL_PATH', path: value.path, repositoryPasswordCredentialId: passwordId }
        : {
            type: 'S3',
            endpoint: value.endpoint,
            bucket: value.bucket,
            prefix: value.prefix || null,
            credentialId: value.credentialId,
            repositoryPasswordCredentialId: passwordId,
          };

    this.dialogRef.close({
      name: value.name,
      description: value.description || null,
      mode: value.mode,
      config,
      enabled: true,
    });
  }

  protected get valid(): boolean {
    const value = this.form.getRawValue();
    if (!value.name) {
      return false;
    }
    if (value.mode === 'RESTIC' && !value.repositoryPasswordCredentialId) {
      return false;
    }
    return value.type === 'LOCAL_PATH'
      ? !!value.path
      : !!value.endpoint && !!value.bucket && !!value.credentialId;
  }
}
