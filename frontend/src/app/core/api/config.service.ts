import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ImportReport } from './models';

/**
 * Ausgabe und Einspielen der Konfiguration als passwortgeschütztes Archiv.
 *
 * <p>Beides sind POST-Aufrufe, auch die Ausgabe: Ein Passwort in der Adresse stünde im
 * Verlauf des Browsers und in jedem Zugriffsprotokoll.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly http = inject(HttpClient);

  /** Liefert das fertige Archiv als Datei, nicht als JSON — es ist binär. */
  exportArchive(password: string): Observable<Blob> {
    return this.http.post('/api/config/export', { password }, { responseType: 'blob' });
  }

  importArchive(file: File, password: string): Observable<ImportReport> {
    const form = new FormData();
    form.append('file', file);
    form.append('password', password);

    return this.http.post<ImportReport>('/api/config/import', form);
  }
}
