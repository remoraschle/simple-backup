import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Plan, RetentionPolicy, Source, SourceTypeInfo, Target } from './models';

/** Zugriff auf Quellen, Ziele, Pläne und Aufbewahrungsregeln. */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  /** Welche Quelltypen sich hier anlegen lassen -- und warum die anderen nicht. */
  sourceTypes(): Observable<SourceTypeInfo[]> {
    return this.http.get<SourceTypeInfo[]>('/api/sources/types');
  }

  /** Die freigegebenen Blockgeräte, damit man sie auswählt statt sie abzutippen. */
  devices(): Observable<string[]> {
    return this.http.get<string[]>('/api/sources/devices');
  }

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

  listRetentionPolicies(): Observable<RetentionPolicy[]> {
    return this.http.get<RetentionPolicy[]>('/api/retention-policies');
  }

  createRetentionPolicy(request: unknown): Observable<RetentionPolicy> {
    return this.http.post<RetentionPolicy>('/api/retention-policies', request);
  }

  deleteRetentionPolicy(id: string): Observable<void> {
    return this.http.delete<void>(`/api/retention-policies/${id}`);
  }
}
