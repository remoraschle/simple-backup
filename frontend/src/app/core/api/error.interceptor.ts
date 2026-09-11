import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/**
 * Wandelt Fehlerantworten in eine sichtbare Rueckmeldung um.
 *
 * <p>Das Backend liefert RFC-9457-Problemdetails; deren `detail`-Feld ist bereits fuer die
 * Anzeige bereinigt. Nur wenn keines vorhanden ist, wird ein allgemeiner Text gezeigt --
 * rohe Statuscodes helfen niemandem.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const snackBar = inject(MatSnackBar);
  const auth = inject(AuthService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      // Die Sitzungsabfrage darf ohne Anmeldung fehlschlagen, das ist kein Fehlerfall.
      const isSessionProbe = request.url.endsWith('/api/auth/session');

      if (error.status === 401 && !isSessionProbe) {
        auth.handleSessionExpired();
        snackBar.open('Die Sitzung ist abgelaufen. Bitte erneut anmelden.', 'OK', { duration: 6000 });
      } else if (error.status === 403) {
        snackBar.open('Dafuer fehlt die Berechtigung.', 'OK', { duration: 6000 });
      } else if (!isSessionProbe && error.status !== 0) {
        snackBar.open(messageFor(error), 'OK', { duration: 8000 });
      } else if (error.status === 0) {
        snackBar.open('Keine Verbindung zum Server.', 'OK', { duration: 8000 });
      }

      return throwError(() => error);
    }),
  );
};

function messageFor(error: HttpErrorResponse): string {
  const detail = error.error?.detail;
  return typeof detail === 'string' && detail.length > 0 ? detail : 'Die Anfrage ist fehlgeschlagen.';
}
