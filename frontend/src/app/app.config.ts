import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { errorInterceptor } from './core/api/error.interceptor';
import { AuthService } from './core/auth/auth.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),

    provideHttpClient(
      withInterceptors([errorInterceptor]),
      // Passend zur Konfiguration von Spring Security: Der Token kommt im Cookie
      // XSRF-TOKEN und geht im Header X-XSRF-TOKEN zurueck.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),

    // Der Sitzungszustand wird vor dem ersten Rendern geladen. Sonst zeigte die Anwendung
    // beim Neuladen kurz die Anmeldemaske, obwohl die Sitzung noch gueltig ist.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).loadSession())),
  ],
};
