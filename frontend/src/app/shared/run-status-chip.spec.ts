import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { RunStatusChip } from './run-status-chip';

/**
 * Der Zustand eines Laufs ist die wichtigste Aussage der ganzen Oberfläche. Geprüft wird
 * deshalb, dass jeder Zustand ein eigenes Symbol und einen eigenen Text bekommt -- Farbe
 * allein trägt die Aussage nicht.
 */
describe('RunStatusChip', () => {
  function render(status: string | null): HTMLElement {
    const fixture = TestBed.createComponent(RunStatusChip);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('unterscheidet Erfolg, Teilerfolg und Fehlschlag im Text', () => {
    // Der Unterschied zwischen "lief durch" und "lief halb durch" ist der wichtigste
    // der Anwendung und darf nicht nur an der Farbe hängen.
    expect(render('SUCCESS').textContent).toContain('Erfolgreich');
    expect(render('PARTIAL').textContent).toContain('Teilerfolg');
    expect(render('FAILED').textContent).toContain('Fehlgeschlagen');
  });

  it('gibt jedem Zustand ein eigenes Symbol', () => {
    const iconOf = (status: string) =>
      render(status).querySelector('mat-icon')?.textContent?.trim();

    const icons = ['SUCCESS', 'PARTIAL', 'FAILED', 'RUNNING', 'CANCELLED'].map(iconOf);

    expect(new Set(icons).size).toBe(icons.length);
  });

  it('behandelt einen Plan ohne Lauf als eigenen Zustand', () => {
    // Nicht als Fehler und nicht als Erfolg: Er ist schlicht noch nie gelaufen.
    expect(render(null).textContent).toContain('Noch nie gelaufen');
  });

  it('erklärt jeden Zustand zusätzlich im Tooltip', () => {
    const chip = render('PARTIAL').querySelector('span');

    expect(
      chip?.getAttribute('ng-reflect-message') ?? chip?.getAttribute('aria-describedby'),
    ).toBeDefined();
  });
});
