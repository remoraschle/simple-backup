import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { RetentionPanel } from './retention-panel';

/**
 * Die eine Regel, die hier wirklich zählt: Eine Aufbewahrung, die nichts behält, würde beim
 * ersten Aufräumen sämtliche Sicherungen löschen. Das Backend lehnt sie ab — die Oberfläche
 * darf sie gar nicht erst absenden lassen.
 */
describe('RetentionPanel', () => {
  let fixture: ComponentFixture<RetentionPanel>;
  let http: HttpTestingController;

  function submitButton(): HTMLButtonElement {
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ) as HTMLButtonElement[];

    const submit = buttons.find((button) => button.type === 'submit');
    if (!submit) {
      throw new Error('Kein Absenden-Knopf gefunden');
    }
    return submit;
  }

  function setRule(values: Record<string, number | null>): void {
    const form = (
      fixture.componentInstance as unknown as { form: { patchValue(v: unknown): void } }
    ).form;
    form.patchValue({ name: 'Standard', ...values });
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RetentionPanel],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    fixture = TestBed.createComponent(RetentionPanel);
    http = TestBed.inject(HttpTestingController);

    fixture.detectChanges();
    http.expectOne('/api/retention-policies').flush([]);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('lässt eine Regel zu, die mindestens eine Stufe behält', () => {
    setRule({ keepDaily: 7 });

    expect(submitButton().disabled).toBe(false);
  });

  it('verhindert eine Regel, die nichts behält', () => {
    setRule({
      keepLast: null,
      keepHourly: null,
      keepDaily: null,
      keepWeekly: null,
      keepMonthly: null,
      keepYearly: null,
      keepWithinDays: null,
    });

    expect(submitButton().disabled).toBe(true);
  });

  it('erklärt, warum eine leere Regel nicht geht', () => {
    // Ein deaktivierter Knopf ohne Begründung ist eine Sackgasse.
    setRule({ keepDaily: null, keepWeekly: null, keepMonthly: null, keepYearly: null });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'würde beim ersten Aufräumen sämtliche Sicherungen löschen',
    );
  });

  it('zählt auch eine Null nicht als behalten', () => {
    // "0 täglich" behält nichts -- der Unterschied zu "leer" ist für den Menschen keiner.
    setRule({ keepDaily: 0, keepWeekly: null, keepMonthly: null, keepYearly: null });

    expect(submitButton().disabled).toBe(true);
  });
});
