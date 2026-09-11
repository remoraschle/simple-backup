import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

/**
 * Uebersicht.
 *
 * <p>Zeigt derzeit bewusst nur den leeren Zustand: Es gibt noch keine Plaene, weil der
 * Ausfuehrungsteil erst in M1 und M2 entsteht. Ein Dashboard mit erfundenen Zahlen waere
 * ausgerechnet hier die schlechteste Wahl -- man wuerde ihm spaeter nicht mehr glauben.
 */
@Component({
  selector: 'sb-dashboard',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './dashboard.html',
})
export class Dashboard {}
