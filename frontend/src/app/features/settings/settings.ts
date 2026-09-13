import { Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { PageHeader } from '../../shared/page-header';
import { ChannelsPanel } from './channels-panel';
import { CredentialsPanel } from './credentials-panel';
import { RetentionPanel } from './retention-panel';

/**
 * Einstellungen.
 *
 * <p>Drei Dinge, die alle Pläne betreffen und jeweils für sich stehen: woher die Zugänge
 * kommen, wohin gemeldet wird und wie lange Sicherungen bleiben. Getrennte Reiter statt
 * einer langen Seite — man kommt jeweils wegen genau einer der drei Fragen hierher.
 */
@Component({
  selector: 'sb-settings',
  imports: [MatTabsModule, PageHeader, ChannelsPanel, CredentialsPanel, RetentionPanel],
  templateUrl: './settings.html',
})
export class Settings {}
