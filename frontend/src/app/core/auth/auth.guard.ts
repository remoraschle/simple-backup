import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Schuetzt die Anwendungsrouten.
 *
 * <p>Das ist reiner Bedienkomfort, keine Sicherheitsmassnahme: Die eigentliche Pruefung
 * macht das Backend bei jeder Anfrage. Ein manipuliertes Frontend kaeme hier vorbei, aber
 * nicht an der API.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { redirect: state.url } });
  }

  // Solange das Erstpasswort gilt, fuehrt jeder Weg zum Passwortwechsel.
  if (auth.mustChangePassword() && !state.url.startsWith('/passwort')) {
    return router.createUrlTree(['/passwort']);
  }

  return true;
};

/** Fuer Routen, die nur Administratoren aendern duerfen. */
export const adminGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const allowed = authGuard(route, state);
  if (allowed !== true) {
    return allowed;
  }
  return auth.isAdmin() ? true : router.createUrlTree(['/']);
};
