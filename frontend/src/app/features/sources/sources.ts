import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CatalogService } from '../../core/api/catalog.service';
import { Source } from '../../core/api/models';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';
import { SourceDialog } from './source-dialog';

@Component({
  selector: 'sb-sources',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
    PageHeader,
  ],
  templateUrl: './sources.html',
})
export class Sources {
  private readonly catalog = inject(CatalogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly sources = signal<Source[]>([]);
  protected readonly loading = signal(true);
  protected readonly columns = ['name', 'type', 'source', 'excludes', 'actions'];

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.catalog.listSources().subscribe({
      next: (sources) => {
        this.sources.set(sources);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected create(): void {
    this.dialog
      .open(SourceDialog)
      .afterClosed()
      .subscribe((request) => {
        if (!request) {
          return;
        }
        this.catalog.createSource(request).subscribe({
          next: () => {
            this.snackBar.open('Quelle angelegt', 'OK', { duration: 4000 });
            this.reload();
          },
          // Die Fehlermeldung zeigt bereits der Interceptor; sie erklärt etwa, dass ein
          // Pfad im Backend-Container nicht eingehängt ist.
          error: () => undefined,
        });
      });
  }

  protected remove(source: Source): void {
    if (!confirm(`Quelle "${source.name}" wirklich löschen?`)) {
      return;
    }
    this.catalog.deleteSource(source.id).subscribe({
      next: () => {
        this.snackBar.open('Quelle gelöscht', 'OK', { duration: 4000 });
        this.reload();
      },
      error: () => undefined,
    });
  }

  protected pathsOf(source: Source): string[] {
    return source.config.type === 'LOCAL_PATH' ? source.config.paths : [];
  }

  /** Ein Satz, der sagt, woher die Daten kommen — je Art ein anderer. */
  protected describe(source: Source): string {
    switch (source.config.type) {
      case 'LOCAL_PATH':
        return source.config.paths.join(', ');
      case 'POSTGRES':
        return `${source.config.username}@${source.config.host}:${source.config.port} — ${
          source.config.databases.length === 0
            ? 'alle Datenbanken'
            : source.config.databases.join(', ')
        }`;
      case 'GITHUB':
        return `${source.config.owner} — ${
          source.config.repositories.length === 0
            ? 'alle Repositories'
            : source.config.repositories.join(', ')
        }`;
      case 'S3':
        return `${source.config.bucket}${source.config.prefix ? '/' + source.config.prefix : ''} auf ${
          source.config.endpoint
        }`;
      default:
        return `${source.config.username}@${source.config.host}:${source.config.path}`;
    }
  }

  protected typeLabel(source: Source): string {
    switch (source.type) {
      case 'LOCAL_PATH':
        return 'Verzeichnis';
      case 'POSTGRES':
        return 'PostgreSQL';
      case 'GITHUB':
        return 'GitHub';
      case 'S3':
        return 'S3';
      case 'SFTP':
        return 'SFTP';
      default:
        return source.type;
    }
  }

  protected excludesOf(source: Source): string[] {
    return source.config.type === 'LOCAL_PATH' ? source.config.excludes : [];
  }
}
