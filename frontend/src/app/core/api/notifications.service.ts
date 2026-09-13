import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { NotificationChannel, OutboxEntry, Page } from './models';

/** Zugriff auf Kanäle und Postausgang. */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);

  listChannels(): Observable<NotificationChannel[]> {
    return this.http.get<NotificationChannel[]>('/api/notifications/channels');
  }

  createChannel(request: unknown): Observable<NotificationChannel> {
    return this.http.post<NotificationChannel>('/api/notifications/channels', request);
  }

  updateChannel(id: string, request: unknown): Observable<NotificationChannel> {
    return this.http.put<NotificationChannel>(`/api/notifications/channels/${id}`, request);
  }

  deleteChannel(id: string): Observable<void> {
    return this.http.delete<void>(`/api/notifications/channels/${id}`);
  }

  /**
   * Legt eine Probemeldung in den Postausgang.
   *
   * <p>Sie nimmt denselben Weg wie ein echter Alarm — ein Test, der einen anderen Weg nimmt
   * als der Ernstfall, testet den falschen.
   */
  testChannel(id: string): Observable<{ outboxId: string }> {
    return this.http.post<{ outboxId: string }>(`/api/notifications/channels/${id}/test`, null);
  }

  outbox(size = 25): Observable<Page<OutboxEntry>> {
    return this.http.get<Page<OutboxEntry>>('/api/notifications/outbox', {
      params: { size: String(size) },
    });
  }
}
