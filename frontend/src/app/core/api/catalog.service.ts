import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Plan, Source, Target } from './models';

/** Zugriff auf Quellen, Ziele und Pläne. */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  listSources(): Observable<Source[]> {
    return this.http.get<Source[]>('/api/sources');
  }

  createSource(request: unknown): Observable<Source> {
    return this.http.post<Source>('/api/sources', request);
  }

  updateSource(id: string, request: unknown): Observable<Source> {
    return this.http.put<Source>(`/api/sources/${id}`, request);
  }

  deleteSource(id: string): Observable<void> {
    return this.http.delete<void>(`/api/sources/${id}`);
  }

  listTargets(): Observable<Target[]> {
    return this.http.get<Target[]>('/api/targets');
  }

  createTarget(request: unknown): Observable<Target> {
    return this.http.post<Target>('/api/targets', request);
  }

  deleteTarget(id: string): Observable<void> {
    return this.http.delete<void>(`/api/targets/${id}`);
  }

  listPlans(): Observable<Plan[]> {
    return this.http.get<Plan[]>('/api/plans');
  }

  getPlan(id: string): Observable<Plan> {
    return this.http.get<Plan>(`/api/plans/${id}`);
  }

  createPlan(request: unknown): Observable<Plan> {
    return this.http.post<Plan>('/api/plans', request);
  }

  updatePlan(id: string, request: unknown): Observable<Plan> {
    return this.http.put<Plan>(`/api/plans/${id}`, request);
  }

  deletePlan(id: string): Observable<void> {
    return this.http.delete<void>(`/api/plans/${id}`);
  }
}
