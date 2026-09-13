import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ConfigService } from '../../core/api/config.service';
import { ImportReport } from '../../core/api/models';

/** Kürzer ergibt kein Archiv, das man irgendwo liegen lassen möchte. Auch das Backend lehnt es ab. */
const MINIMUM_PASSWORD_LENGTH = 12;

/**
 * Sicherung der Konfiguration selbst.
 *
 * <p>Der Ausweg aus dem einen Fehler, den diese Anwendung sonst nicht verzeiht: Ohne den
 * Masterkey ist die eigene Datenbank wertlos — alle Zugangsdaten darin sind unlesbar, und
 * damit kommt man an kein Repository mehr heran. Dieses Archiv hängt nicht am Masterkey,
 * sondern an einem selbst gewählten Passwort, und gehört deshalb woandershin als die
 * Anwendung.
 */
@Component({
  selector: 'sb-archive-panel',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './archive-panel.html',
})
export class ArchivePanel {
  private readonly config = inject(ConfigService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly formBuilder = inject(FormBuilder);

  protected readonly busy = signal(false);
  protected readonly report = signal<ImportReport | null>(null);
  protected readonly selectedFile = signal<File | null>(null);

  protected readonly exportForm = this.formBuilder.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(MINIMUM_PASSWORD_LENGTH)]],
    repeated: ['', Validators.required],
  });

  protected readonly importForm = this.formBuilder.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(MINIMUM_PASSWORD_LENGTH)]],
  });

  protected readonly minimumLength = MINIMUM_PASSWORD_LENGTH;

  /**
   * Ein vertipptes Passwort fällt sonst erst auf, wenn das Archiv gebraucht wird — und dann
   * ist es unwiderruflich: Es gibt keine Hintertür, mit der sich ein Archiv ohne sein
   * Passwort öffnen ließe.
   */
  protected passwordsMatch(): boolean {
    const value = this.exportForm.getRawValue();
    return value.password === value.repeated;
  }

  protected exportArchive(): void {
    if (this.exportForm.invalid || !this.passwordsMatch()) {
      return;
    }
    this.busy.set(true);

    this.config.exportArchive(this.exportForm.getRawValue().password).subscribe({
      next: (archive) => {
        this.download(archive);
        this.busy.set(false);
        this.exportForm.reset();
        this.snackBar.open('Archiv erstellt. Es gehört nicht auf denselben Rechner.', 'OK', {
          duration: 8000,
        });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.reportBlobError(error);
      },
    });
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files?.[0] ?? null);
    this.report.set(null);
  }

  protected importArchive(): void {
    const file = this.selectedFile();
    if (!file || this.importForm.invalid) {
      return;
    }
    this.busy.set(true);

    this.config.importArchive(file, this.importForm.getRawValue().password).subscribe({
      next: (report) => {
        this.report.set(report);
        this.busy.set(false);
        this.importForm.reset();
      },
      error: () => this.busy.set(false),
    });
  }

  /** Für die Anzeige: aus `{ "Quelle": 2 }` wird `2 × Quelle`. */
  protected entries(counts: Record<string, number>): { kind: string; count: number }[] {
    return Object.entries(counts).map(([kind, count]) => ({ kind, count }));
  }

  protected skippedEntries(skipped: Record<string, string[]>): { kind: string; names: string }[] {
    return Object.entries(skipped).map(([kind, names]) => ({ kind, names: names.join(', ') }));
  }

  protected nothingImported(report: ImportReport): boolean {
    return Object.keys(report.imported).length === 0;
  }

  private download(archive: Blob): void {
    const url = URL.createObjectURL(archive);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `simple-backup-${new Date().toISOString().slice(0, 10)}.sbexp`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  /**
   * Die Ausgabe kommt als Datei, also auch die Fehlermeldung.
   *
   * <p>Der allgemeine Fehlerbehandler liest das Feld `detail` aus der Antwort; bei einer
   * Datei-Antwort steht dort ein Blob statt eines Textes. Ohne dieses Auspacken bekäme man
   * hier "Die Anfrage ist fehlgeschlagen" und wüsste nichts.
   */
  private reportBlobError(error: unknown): void {
    const body = (error as { error?: unknown }).error;
    if (!(body instanceof Blob)) {
      return;
    }
    body.text().then((text) => {
      const detail = this.detailOf(text);
      if (detail) {
        this.snackBar.open(detail, 'OK', { duration: 8000 });
      }
    });
  }

  private detailOf(text: string): string | null {
    try {
      const parsed: unknown = JSON.parse(text);
      const detail = (parsed as { detail?: unknown }).detail;
      return typeof detail === 'string' ? detail : null;
    } catch {
      return null;
    }
  }
}
