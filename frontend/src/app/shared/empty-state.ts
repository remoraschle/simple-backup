import { Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

/**
 * Der leere Zustand einer Liste.
 *
 * <p>Sagt, was fehlt und was als Nächstes zu tun ist, statt nur eine leere Fläche zu zeigen.
 */
@Component({
  selector: 'sb-empty-state',
  imports: [MatCardModule, MatIconModule],
  template: `
    <mat-card class="max-w-2xl">
      <mat-card-content class="flex flex-col items-start gap-4 py-8">
        <mat-icon class="scale-150 text-on-surface-variant">{{ icon() }}</mat-icon>
        <div>
          <h2 class="m-0 text-lg font-medium">{{ title() }}</h2>
          <p class="mt-2 mb-0 max-w-prose text-on-surface-variant">{{ description() }}</p>
        </div>
        <ng-content />
      </mat-card-content>
    </mat-card>
  `,
})
export class EmptyState {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
