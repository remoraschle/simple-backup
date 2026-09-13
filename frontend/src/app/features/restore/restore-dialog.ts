import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

/** Fragt, wohin wiederhergestellt wird. */
@Component({
  selector: 'sb-restore-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  template: `
    <h2 mat-dialog-title>Wiederherstellen</h2>

    <mat-dialog-content>
      <form [formGroup]="form" class="flex w-full flex-col gap-2 pt-2 sm:w-120">
        <p class="mt-0 mb-2">
          @if (data.count === 0) {
            Der vollständige Stand wird zurückgeholt.
          } @else {
            {{ data.count }} ausgewählte Einträge werden zurückgeholt.
          }
        </p>

        <mat-form-field>
          <mat-label>Zielverzeichnis</mat-label>
          <input matInput formControlName="targetPath" placeholder="/mnt/nas/wiederhergestellt" />
          <mat-hint>
            Muss eingehängt sein — dieselbe Regel wie bei Quellen. Ein nicht eingehängter Pfad wird
            abgelehnt, statt still ins Leere zu schreiben.
          </mat-hint>
        </mat-form-field>

        <p class="mt-6 mb-0 flex gap-2 rounded-md bg-surface-container-high p-3 text-sm">
          <mat-icon class="h-5 w-5 shrink-0 text-xl leading-5 text-on-surface-variant">
            info_outline
          </mat-icon>
          <span>
            Vorhandene Dateien im Zielverzeichnis werden überschrieben. Ein leeres Verzeichnis zu
            wählen ist die sicherere Wahl — vergleichen kann man danach immer noch.
          </span>
        </p>
      </form>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button matButton mat-dialog-close>Abbrechen</button>
      <button matButton="filled" (click)="confirm()" [disabled]="form.invalid">Zurückholen</button>
    </mat-dialog-actions>
  `,
})
export class RestoreDialog {
  protected readonly data = inject<{ count: number }>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<RestoreDialog>);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    targetPath: ['', [Validators.required, Validators.pattern(/^\/.+/)]],
  });

  protected confirm(): void {
    if (this.form.valid) {
      this.dialogRef.close(this.form.getRawValue().targetPath);
    }
  }
}
