import { describe, expect, it } from 'vitest';
import { formatBytes, formatDuration, formatRelative } from './format';

describe('formatBytes', () => {
  it('zeigt kleine Werte unverändert in Byte', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
  });

  it('nutzt binäre Präfixe wie der Dateimanager', () => {
    // 1024 und nicht 1000: Sonst weicht die Anzeige von dem ab, was das Betriebssystem
    // für dieselbe Datei nennt.
    expect(formatBytes(1024)).toBe('1.0 KiB');
    expect(formatBytes(1024 * 1024)).toBe('1.0 MiB');
    expect(formatBytes(5 * 1024 * 1024 * 1024)).toBe('5.0 GiB');
  });

  it('zeigt ab zehn Einheiten keine Nachkommastelle mehr', () => {
    expect(formatBytes(50 * 1024 * 1024)).toBe('50 MiB');
  });

  it('kommt mit fehlenden Werten zurecht', () => {
    // Vor dem ersten Lauf sind die Kennzahlen unbekannt.
    expect(formatBytes(null)).toBe('–');
    expect(formatBytes(undefined)).toBe('–');
  });
});

describe('formatDuration', () => {
  it('zeigt Sekunden, Minuten und Stunden passend gestaffelt', () => {
    expect(formatDuration(45)).toBe('45 s');
    expect(formatDuration(125)).toBe('2 min 5 s');
    expect(formatDuration(7265)).toBe('2 h 1 min');
  });

  it('kommt mit fehlenden Werten zurecht', () => {
    expect(formatDuration(null)).toBe('–');
  });
});

describe('formatRelative', () => {
  it('beschreibt Vergangenes als Abstand', () => {
    const vorZweiStunden = new Date(Date.now() - 2 * 3600 * 1000).toISOString();

    expect(formatRelative(vorZweiStunden)).toBe('vor 2 Stunden');
  });

  it('beschreibt Künftiges ebenfalls als Abstand', () => {
    // Der nächste Termin eines Plans liegt in der Zukunft.
    const inDreiStunden = new Date(Date.now() + 3 * 3600 * 1000).toISOString();

    expect(formatRelative(inDreiStunden)).toBe('in 3 Stunden');
  });

  it('nennt einen fehlenden Zeitpunkt beim Namen', () => {
    // Ein Plan, der noch nie lief, ist der interessante Fall -- nicht ein leeres Feld.
    expect(formatRelative(null)).toBe('nie');
    expect(formatRelative(undefined)).toBe('nie');
  });

  it('setzt die Einzahl, wo nur eine Einheit vergangen ist', () => {
    // "vor 1 Minuten" stand nach jedem frisch beendeten Lauf in der Liste.
    const vorEinerMinute = new Date(Date.now() - 65 * 1000).toISOString();
    const vorEinerStunde = new Date(Date.now() - 3600 * 1000).toISOString();
    // Knapp ueber einem Tag: Genau 86400 Sekunden liegen auf der Grenze, und die
    // Laufzeit des Tests selbst entscheidet dann, auf welcher Seite der Wert landet.
    const inEinemTag = new Date(Date.now() + 87000 * 1000).toISOString();

    expect(formatRelative(vorEinerMinute)).toBe('vor 1 Minute');
    expect(formatRelative(vorEinerStunde)).toBe('vor 1 Stunde');
    expect(formatRelative(inEinemTag)).toBe('in 1 Tag');
  });

  it('rundet auf Tage, wenn es lange her ist', () => {
    const vorDreiTagen = new Date(Date.now() - 3 * 86400 * 1000).toISOString();

    expect(formatRelative(vorDreiTagen)).toBe('vor 3 Tagen');
  });
});
