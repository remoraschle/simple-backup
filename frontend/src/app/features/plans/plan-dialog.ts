import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CatalogService } from '../../core/api/catalog.service';

/** Gebräuchliche Zeitpläne, damit niemand cron-Syntax nachschlagen muss. */
const SCHEDULE_PRESETS = [
  { label: 'Täglich um 02:00', value: '0 0 2 * * *' },
  { label: 'Täglich um 22:00', value: '0 0 22 * * *' },
  { label: 'Alle 6 Stunden', value: '0 0 */6 * * *' },
  { label: 'Stündlich', value: '0 0 * * * *' },
  { label: 'Sonntags um 03:00', value: '0 0 3 * * SUN' },
] as const;

@Component({
  selector: 'sb-plan-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  templateUrl: './plan-dialog.html',
})
export class PlanDialog {
  private readonly dialogRef = inject(MatDialogRef<PlanDialog>);
  private readonly catalog = inject(CatalogService);

  protected readonly presets = SCHEDULE_PRESETS;
  protected readonly sources = toSignal(this.catalog.listSources(), { initialValue: [] });
  protected readonly targets = toSignal(this.catalog.listTargets(), { initialValue: [] });

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: [''],
    sourceId: ['', Validators.required],
    targetIds: [[] as string[], Validators.required],
    cronExpression: ['0 0 2 * * *', Validators.required],
    timezone: ['Europe/Zurich', Validators.required],
    enabled: [true],
    timeoutMinutes: [360, [Validators.required, Validators.min(1)]],
  });

  protected usePreset(value: string): void {
    this.form.controls.cronExpression.setValue(value);
  }

  protected save(): void {
    if (this.form.invalid || this.form.getRawValue().targetIds.length === 0) {
      return;
    }
    const value = this.form.getRawValue();

    this.dialogRef.close({
      name: value.name,
      description: value.description || null,
      sourceId: value.sourceId,
      targetIds: value.targetIds,
      retentionPolicyId: null,
      cronExpression: value.cronExpression,
      timezone: value.timezone,
      enabled: value.enabled,
      timeoutMinutes: value.timeoutMinutes,
      maxRetries: 2,
      missedRunPolicy: 'SKIP',
      notifyOn: 'FAILURE',
      expectedIntervalMinutes: null,
    });
  }
}
