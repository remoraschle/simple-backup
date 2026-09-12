import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

interface NavigationEntry {
  readonly path: string;
  readonly label: string;
  readonly icon: string;
}

@Component({
  selector: 'sb-shell',
  imports: [
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatMenuModule,
    MatSidenavModule,
    MatToolbarModule,
    MatTooltipModule,
  ],
  templateUrl: './shell.html',
})
export class Shell {
  private readonly auth = inject(AuthService);

  protected readonly session = this.auth.session;
  protected readonly sidenavOpen = signal(true);

  protected readonly navigation: readonly NavigationEntry[] = [
    { path: '/', label: 'Übersicht', icon: 'dashboard' },
    { path: '/plaene', label: 'Pläne', icon: 'event_repeat' },
    { path: '/quellen', label: 'Quellen', icon: 'folder_open' },
    { path: '/ziele', label: 'Ziele', icon: 'save' },
    { path: '/laeufe', label: 'Läufe', icon: 'history' },
    { path: '/einstellungen', label: 'Einstellungen', icon: 'settings' },
  ];

  protected toggleSidenav(): void {
    this.sidenavOpen.update((open) => !open);
  }

  protected logout(): void {
    this.auth.logout();
  }
}
