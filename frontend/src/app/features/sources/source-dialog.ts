import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

/**
 * Anlegen einer Quelle.
 *
 * <p>Derzeit nur Verzeichnisse: Alles andere ist im Backend noch nicht umgesetzt, und ein
 * Formular für etwas anzubieten, das beim ersten Lauf scheitert, wäre irreführend.
 */
@Component({
  selector: 'sb-source-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
  ],
  templateUrl: './source-dialog.html',
})
export class SourceDialog {
  private readonly dialogRef = inject(MatDialogRef<SourceDialog>);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: [''],
    paths: ['', Validators.required],
    excludes: [''],
    oneFileSystem: [false],
  });

  protected save(): void {
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();

    this.dialogRef.close({
      name: value.name,
      description: value.description || null,
      config: {
        type: 'LOCAL_PATH',
        paths: splitLines(value.paths),
        excludes: splitLines(value.excludes),
        oneFileSystem: value.oneFileSystem,
      },
    });
  }
}

/** Eine Angabe je Zeile — einfacher einzugeben als eine Liste mit Plus-Knopf. */
function splitLines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}
