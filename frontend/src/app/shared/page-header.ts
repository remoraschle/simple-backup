import { Component, input } from '@angular/core';

/** Gemeinsame Überschrift der Seiten, damit sie nicht überall leicht anders aussieht. */
@Component({
  selector: 'sb-page-header',
  template: `
    <div class="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 class="m-0 text-2xl font-medium">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="mt-1 mb-0 max-w-prose text-on-surface-variant">{{ subtitle() }}</p>
        }
      </div>
      <ng-content />
    </div>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
}
