import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthService } from './auth.service';
import { SessionInfo } from './session-info';

const ADMIN_SESSION: SessionInfo = {
  authenticated: true,
  username: 'admin',
  role: 'ADMIN',
  mustChangePassword: false,
};

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // Das Abmelden navigiert zur Anmeldemaske; ohne diese Route liefe die
        // Navigation in einen unbehandelten Fehler und faerbte den Testlauf ein.
        provideRouter([{ path: 'login', children: [] }]),
      ],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('startet abgemeldet', () => {
    expect(service.isAuthenticated()).toBe(false);
  });

  it('uebernimmt den Sitzungszustand vom Backend', () => {
    service.loadSession().subscribe();
    http.expectOne('/api/auth/session').flush(ADMIN_SESSION);

    expect(service.isAuthenticated()).toBe(true);
    expect(service.isAdmin()).toBe(true);
    expect(service.session().username).toBe('admin');
  });

  it('bleibt abgemeldet, wenn die Sitzungsabfrage fehlschlaegt', () => {
    // Ohne Anmeldung antwortet der Endpunkt zwar mit 200, aber ein Netzfehler darf die
    // Anwendung nicht in einen scheinbar angemeldeten Zustand versetzen.
    service.loadSession().subscribe();
    http.expectOne('/api/auth/session').error(new ProgressEvent('network'));

    expect(service.isAuthenticated()).toBe(false);
  });

  it('meldet sich als Formular an, wie Spring Security es erwartet', () => {
    service.login('admin', 'geheim').subscribe();

    const login = http.expectOne('/api/auth/login');
    expect(login.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(login.request.body).toContain('username=admin');
    login.flush({});

    // Nach dem Login wird der Zustand frisch geladen, statt ihn zu erraten.
    http.expectOne('/api/auth/session').flush(ADMIN_SESSION);
    expect(service.isAuthenticated()).toBe(true);
  });

  it('verwirft den Zustand auch dann, wenn das Abmelden serverseitig scheitert', () => {
    service.loadSession().subscribe();
    http.expectOne('/api/auth/session').flush(ADMIN_SESSION);

    service.logout();
    http.expectOne('/api/auth/logout').error(new ProgressEvent('network'));

    expect(service.isAuthenticated()).toBe(false);
  });
});
