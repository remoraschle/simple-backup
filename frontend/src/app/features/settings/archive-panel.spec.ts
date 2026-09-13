import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ArchivePanel } from './archive-panel';

/**
 * Das Archiv lässt sich ohne sein Passwort nicht öffnen — es gibt keine Hintertür. Ein
 * Vertipper beim Anlegen fällt deshalb erst im Ernstfall auf, und dann ist es zu spät.
 * Genau davor schützt diese Oberfläche, und genau das wird hier geprüft.
 */
describe('ArchivePanel', () => {
  let fixture: ComponentFixture<ArchivePanel>;
  let http: HttpTestingController;

  function component(): {
    exportForm: { patchValue(value: unknown): void };
    entries(counts: Record<string, number>): { kind: string; count: number }[];
  } {
    return fixture.componentInstance as unknown as {
      exportForm: { patchValue(value: unknown): void };
      entries(counts: Record<string, number>): { kind: string; count: number }[];
    };
  }

  function exportButton(): HTMLButtonElement {
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ) as HTMLButtonElement[];

    const submit = buttons.find((button) => button.textContent?.includes('herunterladen'));
    if (!submit) {
      throw new Error('Kein Knopf zum Herunterladen gefunden');
    }
    return submit;
  }

  function setPasswords(password: string, repeated: string): void {
    component().exportForm.patchValue({ password, repeated });
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ArchivePanel],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    fixture = TestBed.createComponent(ArchivePanel);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('lässt ein ausreichend langes und wiederholtes Passwort zu', () => {
    setPasswords('archivpasswort-2026', 'archivpasswort-2026');

    expect(exportButton().disabled).toBe(false);
  });

  it('verhindert ein Archiv mit vertipptem Passwort', () => {
    setPasswords('archivpasswort-2026', 'archivpasswort-2027');

    expect(exportButton().disabled).toBe(true);
  });

  it('sagt, warum es nicht weitergeht', () => {
    // Ein deaktivierter Knopf ohne Begründung ist eine Sackgasse.
    setPasswords('archivpasswort-2026', 'archivpasswort-2027');

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('stimmen nicht überein');
  });

  it('verhindert ein zu kurzes Passwort', () => {
    // Ein Archiv mit sämtlichen Zugangsdaten liegt naturgemäß dort, wo es jemand findet.
    setPasswords('kurz', 'kurz');

    expect(exportButton().disabled).toBe(true);
  });

  it('warnt davor, das Archiv neben der Anwendung liegen zu lassen', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Es gehört nicht neben die Anwendung',
    );
  });

  it('zählt das Ergebnis eines Einspielens je Art auf', () => {
    expect(component().entries({ Quelle: 2, Plan: 1 })).toEqual([
      { kind: 'Quelle', count: 2 },
      { kind: 'Plan', count: 1 },
    ]);
  });
});
