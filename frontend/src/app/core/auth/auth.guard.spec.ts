import { TestBed } from '@angular/core/testing';
import { Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach } from 'vitest';
import { adminGuard, authGuard } from './auth.guard';
import { AuthService } from './auth.service';

/**
 * Der Guard ist Bedienkomfort, keine Sicherheitsmassnahme -- die echte Pruefung macht das
 * Backend. Getestet wird deshalb das Weiterleitungsverhalten, nicht "Sicherheit".
 */
describe('authGuard', () => {
  let authenticated: ReturnType<typeof signal<boolean>>;
  let mustChange: ReturnType<typeof signal<boolean>>;
  let admin: ReturnType<typeof signal<boolean>>;

  beforeEach(() => {
    authenticated = signal(false);
    mustChange = signal(false);
    admin = signal(false);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: authenticated,
            mustChangePassword: mustChange,
            isAdmin: admin,
          },
        },
      ],
    });
  });

  function run(guard: typeof authGuard, url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      guard({} as never, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  it('leitet ohne Anmeldung zur Anmeldemaske und merkt sich das Ziel', () => {
    const result = run(authGuard, '/plaene');

    expect(result).toBeInstanceOf(UrlTree);
    const tree = result as UrlTree;
    expect(tree.toString()).toContain('/login');
    expect(tree.queryParams['redirect']).toBe('/plaene');
  });

  it('laesst angemeldete Benutzer durch', () => {
    authenticated.set(true);

    expect(run(authGuard, '/plaene')).toBe(true);
  });

  it('erzwingt den Passwortwechsel vor jeder anderen Seite', () => {
    authenticated.set(true);
    mustChange.set(true);

    const result = run(authGuard, '/plaene');

    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toContain('/passwort');
  });

  it('laesst die Passwortseite selbst zu, sonst gaebe es eine Endlosschleife', () => {
    authenticated.set(true);
    mustChange.set(true);

    expect(run(authGuard, '/passwort')).toBe(true);
  });

  it('haelt Nur-Lese-Benutzer von Administrationsseiten fern', () => {
    authenticated.set(true);
    admin.set(false);

    const result = run(adminGuard, '/einstellungen');

    expect(result).toBeInstanceOf(UrlTree);
  });

  it('laesst Administratoren auf Administrationsseiten', () => {
    authenticated.set(true);
    admin.set(true);

    expect(run(adminGuard, '/einstellungen')).toBe(true);
  });
});
