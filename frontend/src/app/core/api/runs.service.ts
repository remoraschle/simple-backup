import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, NgZone, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FinishedEvent, LogEvent, Page, ProgressEvent, Run, RunDetail, StepEvent } from './models';

/** Ein Ereignis aus dem Live-Datenstrom, nach Art unterschieden. */
export type RunStreamEvent =
  | { readonly kind: 'log'; readonly payload: LogEvent }
  | { readonly kind: 'progress'; readonly payload: ProgressEvent }
  | { readonly kind: 'step'; readonly payload: StepEvent }
  | { readonly kind: 'finished'; readonly payload: FinishedEvent };

@Injectable({ providedIn: 'root' })
export class RunsService {
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);

  list(planId?: string, page = 0, size = 25): Observable<Page<Run>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (planId) {
      params = params.set('planId', planId);
    }
    return this.http.get<Page<Run>>('/api/runs', { params });
  }

  get(id: string): Observable<RunDetail> {
    return this.http.get<RunDetail>(`/api/runs/${id}`);
  }

  readLog(id: string): Observable<string> {
    return this.http.get(`/api/runs/${id}/log`, { responseType: 'text' });
  }

  start(planId: string): Observable<{ runId: string }> {
    return this.http.post<{ runId: string }>('/api/runs/start', null, {
      params: new HttpParams().set('planId', planId),
    });
  }

  cancel(id: string): Observable<void> {
    return this.http.post<void>(`/api/runs/${id}/cancel`, null);
  }

  /**
   * Verfolgt einen laufenden Lauf.
   *
   * <p>Über die Browser-Schnittstelle `EventSource`: Sie bringt die Wiederverbindung nach
   * einem Netzaussetzer von selbst mit — bei einem Backup, das Stunden läuft, ist das kein
   * Randfall.
   *
   * <p>Die Ereignisse kommen außerhalb der Angular-Zone an und werden deshalb ausdrücklich
   * wieder hineingeholt; sonst bliebe die Anzeige stehen, obwohl Daten eintreffen.
   */
  stream(runId: string): Observable<RunStreamEvent> {
    return new Observable<RunStreamEvent>((subscriber) => {
      const source = new EventSource(`/api/runs/${runId}/stream`);

      const forward =
        <T>(name: RunStreamEvent['kind']) =>
        (event: MessageEvent<string>) =>
          this.zone.run(() =>
            subscriber.next({ kind: name, payload: JSON.parse(event.data) as T } as RunStreamEvent),
          );

      source.addEventListener('log', forward<LogEvent>('log'));
      source.addEventListener('progress', forward<ProgressEvent>('progress'));
      source.addEventListener('step', forward<StepEvent>('step'));
      source.addEventListener('finished', (event: MessageEvent<string>) =>
        this.zone.run(() => {
          subscriber.next({ kind: 'finished', payload: JSON.parse(event.data) as FinishedEvent });
          // Der Lauf ist vorbei; ohne dieses Schließen versuchte der Browser endlos, sich
          // wieder zu verbinden.
          source.close();
          subscriber.complete();
        }),
      );

      source.onerror = () => {
        // Der Browser verbindet sich von selbst neu. Nur wenn er endgültig aufgegeben hat,
        // ist der Datenstrom beendet.
        if (source.readyState === EventSource.CLOSED) {
          this.zone.run(() => subscriber.complete());
        }
      };

      return () => source.close();
    });
  }
}
