import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, of, switchMap, tap } from 'rxjs';
import { ANONYMOUS, LoginProviders, PASSWORD_ONLY, SessionInfo } from './session-info';

/**
 * Haelt den Anmeldezustand.
 *
 * <p>Die Sitzung lebt im HttpOnly-Cookie, nicht hier -- dieser Dienst spiegelt nur, was das
 * Backend sagt. Deshalb gibt es kein Token im Speicher, das jemand per XSS auslesen koennte,
 * und ein Neuladen der Seite verliert die Anmeldung nicht.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly sessionState = signal<SessionInfo>(ANONYMOUS);

  readonly session = this.sessionState.asReadonly();
  readonly isAuthenticated = computed(() => this.sessionState().authenticated);
  readonly isAdmin = computed(() => this.sessionState().role === 'ADMIN');
  readonly mustChangePassword = computed(() => this.sessionState().mustChangePassword);

  /**
   * Wird beim Anwendungsstart aufgerufen. Der Endpunkt ist oeffentlich, damit der
   * Sitzungszustand abgefragt werden kann, ohne absichtlich einen 401 zu erzeugen.
   */
  loadSession(): Observable<SessionInfo> {
    return this.http.get<SessionInfo>('/api/auth/session').pipe(
      tap((session) => this.sessionState.set(session)),
      catchError(() => {
        this.sessionState.set(ANONYMOUS);
        return of(ANONYMOUS);
      }),
    );
  }

  /** Öffentlich erreichbar: Die Anmeldeseite braucht die Auskunft, bevor jemand angemeldet ist. */
  providers(): Observable<LoginProviders> {
    return this.http
      .get<LoginProviders>('/api/auth/providers')
      .pipe(catchError(() => of(PASSWORD_ONLY)));
  }

  /**
   * Das Backend erwartet ein Formular-Login, kein JSON -- so ist der Standardablauf von
   * Spring Security aufgebaut, und den ohne Not zu ersetzen brachte keinen Vorteil.
   */
  login(username: string, password: string): Observable<SessionInfo> {
    const body = new URLSearchParams({ username, password });

    return this.http
      .post('/api/auth/login', body.toString(), {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      })
      .pipe(switchMap(() => this.loadSession()));
  }

  logout(): void {
    this.http.post('/api/auth/logout', null).subscribe({
      next: () => this.finishLogout(),
      // Auch wenn das Abmelden serverseitig scheitert: lokal nicht angemeldet bleiben.
      error: () => this.finishLogout(),
    });
  }

  changePassword(currentPassword: string, newPassword: string): Observable<unknown> {
    return this.http.post('/api/auth/password', { currentPassword, newPassword });
  }

  /** Wird vom Fehler-Interceptor aufgerufen, wenn das Backend 401 meldet. */
  handleSessionExpired(): void {
    if (this.sessionState().authenticated) {
      this.finishLogout();
    }
  }

  private finishLogout(): void {
    this.sessionState.set(ANONYMOUS);
    void this.router.navigate(['/login']);
  }
}
