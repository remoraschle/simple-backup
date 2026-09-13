import { Routes } from '@angular/router';
import { adminGuard, authGuard } from './core/auth/auth.guard';

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
        title: 'Übersicht',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'plaene',
        title: 'Pläne',
        loadComponent: () => import('./features/plans/plans').then((m) => m.Plans),
      },
      {
        path: 'quellen',
        title: 'Quellen',
        loadComponent: () => import('./features/sources/sources').then((m) => m.Sources),
      },
      {
        path: 'ziele',
        title: 'Ziele',
        loadComponent: () => import('./features/targets/targets').then((m) => m.Targets),
      },
      {
        path: 'laeufe',
        title: 'Läufe',
        loadComponent: () => import('./features/runs/runs').then((m) => m.Runs),
      },
      {
        // Die Kennung wird als Eingabe gebunden, siehe withComponentInputBinding.
        path: 'laeufe/:id',
        title: 'Lauf',
        loadComponent: () => import('./features/runs/run-detail').then((m) => m.RunDetail),
      },
      {
        path: 'wiederherstellen',
        title: 'Wiederherstellen',
        loadComponent: () => import('./features/restore/restore').then((m) => m.RestorePage),
      },
      {
        path: 'passwort',
        title: 'Passwort ändern',
        loadComponent: () =>
          import('./features/password-change/password-change').then((m) => m.PasswordChange),
      },
      {
        path: 'einstellungen',
        title: 'Einstellungen',
        canActivate: [adminGuard],
        loadComponent: () => import('./features/settings/settings').then((m) => m.Settings),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
