/** Anzeigehilfen, die in mehreren Ansichten gebraucht werden. */

/** Byte in eine lesbare Größe, mit binären Präfixen wie im Dateimanager. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return '–';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let value = bytes / 1024;
  let unit = 0;

  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** Dauer in Sekunden als kurze, lesbare Angabe. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return '–';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ${Math.round(seconds % 60)} s`;
  }
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

/**
 * Eine Anzahl mit passendem Zaehlwort.
 *
 * <p>Ohne das steht in der Oberfläche „1 Pläne“ und „vor 1 Minuten“ — und genau die Eins ist
 * der häufigste Fall, weil meist ein einzelner Plan gerade gelaufen ist.
 */
export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** Ein Zeitpunkt als Abstand zu jetzt — für eine Übersicht aussagekräftiger als ein Datum. */
export function formatRelative(isoDate: string | null | undefined): string {
  if (!isoDate) {
    return 'nie';
  }
  const seconds = (Date.now() - new Date(isoDate).getTime()) / 1000;
  const future = seconds < 0;
  const absolute = Math.abs(seconds);

  const describe = (): string => {
    if (absolute < 60) return 'wenigen Sekunden';
    if (absolute < 3600) return plural(Math.round(absolute / 60), 'Minute', 'Minuten');
    if (absolute < 86400) return plural(Math.round(absolute / 3600), 'Stunde', 'Stunden');
    return plural(Math.round(absolute / 86400), 'Tag', 'Tagen');
  };

  return future ? `in ${describe()}` : `vor ${describe()}`;
}
