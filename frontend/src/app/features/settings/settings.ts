import { Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { PageHeader } from '../../shared/page-header';
import { ArchivePanel } from './archive-panel';
import { ChannelsPanel } from './channels-panel';
import { CredentialsPanel } from './credentials-panel';
import { RetentionPanel } from './retention-panel';

/**
 * Einstellungen.
 *
 * <p>Was alle Pläne betrifft und jeweils für sich steht: woher die Zugänge kommen, wohin
 * gemeldet wird, wie lange Sicherungen bleiben — und die Sicherung dieser Angaben selbst.
 * Getrennte Reiter statt einer langen Seite: Man kommt jeweils wegen genau einer der Fragen
 * hierher.
 */
@Component({
  selector: 'sb-settings',
  imports: [
    MatTabsModule,
    PageHeader,
    ArchivePanel,
    ChannelsPanel,
    CredentialsPanel,
    RetentionPanel,
  ],
  templateUrl: './settings.html',
})
export class Settings {}
