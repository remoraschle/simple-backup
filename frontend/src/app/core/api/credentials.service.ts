import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type CredentialType =
  | 'PASSWORD'
  | 'API_TOKEN'
  | 'SSH_PRIVATE_KEY'
  | 'S3_KEYPAIR'
  | 'RESTIC_REPOSITORY_PASSWORD'
  | 'PUSHOVER';

/**
 * Ein Zugang, wie ihn die API herausgibt.
 *
 * <p>Ohne Feld für den Wert — der Typ im Backend hat ebenfalls keines und kann folglich
 * keinen herausgeben.
 */
export interface Credential {
  readonly id: string;
  readonly name: string;
  readonly type: CredentialType;
  readonly description: string | null;
  readonly keyVersion: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class CredentialsService {
  private readonly http = inject(HttpClient);

  list(): Observable<Credential[]> {
    return this.http.get<Credential[]>('/api/credentials');
  }

  create(request: {
    name: string;
    type: CredentialType;
    description?: string | null;
    secret: string;
  }): Observable<Credential> {
    return this.http.post<Credential>('/api/credentials', request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/credentials/${id}`);
  }
}
