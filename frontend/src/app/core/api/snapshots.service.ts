import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BrowseResult, CheckOutcome, Restore, RestoreTestOutcome, Snapshot } from './models';

/** Zugriff auf Snapshots, Wiederherstellung und Prüfung. */
@Injectable({ providedIn: 'root' })
export class SnapshotsService {
  private readonly http = inject(HttpClient);

  list(targetId?: string, planId?: string): Observable<Snapshot[]> {
    const params: Record<string, string> = {};
    if (targetId) {
      params['targetId'] = targetId;
    }
    if (planId) {
      params['planId'] = planId;
    }
    return this.http.get<Snapshot[]>('/api/snapshots', { params });
  }

  /** Fragt das Repository selbst — die Datenbank ist nur eine Abschrift. */
  refresh(targetId: string, planId?: string): Observable<Snapshot[]> {
    const params: Record<string, string> = { targetId };
    if (planId) {
      params['planId'] = planId;
    }
    return this.http.post<Snapshot[]>('/api/snapshots/refresh', null, { params });
  }

  browse(snapshotId: string, path?: string): Observable<BrowseResult> {
    return this.http.get<BrowseResult>(`/api/snapshots/${snapshotId}/entries`, {
      params: path ? { path } : {},
    });
  }

  /** Die Adresse für den Download einer einzelnen Datei. */
  fileUrl(snapshotId: string, path: string): string {
    return `/api/snapshots/${snapshotId}/file?path=${encodeURIComponent(path)}`;
  }

  startRestore(request: {
    snapshotId: string;
    targetPath: string;
    includes: string[];
  }): Observable<Restore> {
    return this.http.post<Restore>('/api/snapshots/restores', request);
  }

  restore(id: string): Observable<Restore> {
    return this.http.get<Restore>(`/api/snapshots/restores/${id}`);
  }

  restoreLog(id: string): Observable<{ state: string; lines: string[] }> {
    return this.http.get<{ state: string; lines: string[] }>(`/api/snapshots/restores/${id}/log`);
  }

  check(targetId: string, readDataPercent?: number): Observable<CheckOutcome> {
    const params: Record<string, string> = { targetId };
    if (readDataPercent) {
      params['readDataPercent'] = String(readDataPercent);
    }
    return this.http.post<CheckOutcome>('/api/snapshots/check', null, { params });
  }

  verify(targetId: string): Observable<RestoreTestOutcome> {
    return this.http.post<RestoreTestOutcome>('/api/snapshots/verify', null, {
      params: { targetId },
    });
  }
}
