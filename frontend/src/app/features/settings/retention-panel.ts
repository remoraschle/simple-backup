import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CatalogService } from '../../core/api/catalog.service';
import { plural } from '../../core/api/format';
import { RetentionPolicy } from '../../core/api/models';
import { EmptyState } from '../../shared/empty-state';

/** Ein Vorschlag, der für die meisten Fälle passt. */
const DEFAULT_RULE = { keepDaily: 7, keepWeekly: 4, keepMonthly: 12, keepYearly: 3 };

/**
 * Aufbewahrungsregeln nach dem Großvater-Vater-Sohn-Muster.
 *
 * <p>Eine Regel, die nichts behält, lehnt das Backend ab — sie würde beim ersten Aufräumen
 * sämtliche Sicherungen löschen. Diese Oberfläche verhindert sie deshalb schon hier.
 */
@Component({
  selector: 'sb-retention-panel',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTableModule,
    MatTooltipModule,
    EmptyState,
  ],
  templateUrl: './retention-panel.html',
})
export class RetentionPanel {
  private readonly catalog = inject(CatalogService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly policies = signal<RetentionPolicy[]>([]);
  protected readonly columns = ['name', 'rule', 'actions'];

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    keepLast: [null as number | null, Validators.min(0)],
    keepHourly: [null as number | null, Validators.min(0)],
    keepDaily: [DEFAULT_RULE.keepDaily as number | null, Validators.min(0)],
    keepWeekly: [DEFAULT_RULE.keepWeekly as number | null, Validators.min(0)],
    keepMonthly: [DEFAULT_RULE.keepMonthly as number | null, Validators.min(0)],
    keepYearly: [DEFAULT_RULE.keepYearly as number | null, Validators.min(0)],
    keepWithinDays: [null as number | null, Validators.min(0)],
  });

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.catalog.listRetentionPolicies().subscribe((policies) => this.policies.set(policies));
  }

  /** Ob überhaupt etwas behalten wird. Ohne das löschte die Regel beim ersten Lauf alles. */
  protected keepsSomething(): boolean {
    const value = this.form.getRawValue();
    return [
      value.keepLast,
      value.keepHourly,
      value.keepDaily,
      value.keepWeekly,
      value.keepMonthly,
      value.keepYearly,
      value.keepWithinDays,
    ].some((entry) => (entry ?? 0) > 0);
  }

  protected describe(policy: RetentionPolicy): string {
    const rule = policy.rule;

    const parts = [
      rule.keepLast ? (rule.keepLast === 1 ? 'die letzte' : `die letzten ${rule.keepLast}`) : null,
      rule.keepHourly ? `${rule.keepHourly} stündlich` : null,
      rule.keepDaily ? `${rule.keepDaily} täglich` : null,
      rule.keepWeekly ? `${rule.keepWeekly} wöchentlich` : null,
      rule.keepMonthly ? `${rule.keepMonthly} monatlich` : null,
      rule.keepYearly ? `${rule.keepYearly} jährlich` : null,
      rule.keepWithinDays
        ? `alles der letzten ${plural(rule.keepWithinDays, 'Tag', 'Tage')}`
        : null,
    ].filter((part): part is string => part !== null);

    return parts.join(', ');
  }

  protected save(): void {
    if (this.form.invalid || !this.keepsSomething()) {
      return;
    }
    this.catalog.createRetentionPolicy(this.form.getRawValue()).subscribe({
      next: () => {
        this.snackBar.open('Regel gespeichert', 'OK', { duration: 4000 });
        this.form.reset(DEFAULT_RULE);
        this.reload();
      },
      error: () => undefined,
    });
  }

  protected remove(policy: RetentionPolicy): void {
    if (!confirm(`Regel "${policy.name}" wirklich löschen?`)) {
      return;
    }
    this.catalog.deleteRetentionPolicy(policy.id).subscribe({
      next: () => this.reload(),
      error: () => undefined,
    });
  }
}
