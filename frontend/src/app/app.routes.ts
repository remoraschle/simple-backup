import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Anmelden',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        title: 'Uebersicht',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'passwort',
        title: 'Passwort aendern',
        loadComponent: () =>
          import('./features/password-change/password-change').then((m) => m.PasswordChange),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
