package dev.remo.simplebackup.restic;

/**
 * Eine Meldung aus der JSON-Ausgabe von restic.
 *
 * <p>Versiegelt, weil nur diese drei Arten ausgewertet werden. Alles andere -- und davon gibt
 * es je nach Version einiges -- wird uebergangen statt als Fehler behandelt.
 */
public sealed interface ResticMessage {

    /**
     * Fortschritt waehrend des Laufs.
     *
     * @param percentDone      Anteil zwischen 0 und 1
     * @param secondsRemaining geschaetzte Restzeit, {@code null} solange restic nicht schaetzt
     */
    record Progress(
            double percentDone,
            Long filesDone,
            Long totalFiles,
            Long bytesDone,
            Long totalBytes,
            Long secondsRemaining) implements ResticMessage {

        /** Als ganze Prozent fuer die Anzeige. */
        public int percent() {
            return (int) Math.round(Math.clamp(percentDone, 0.0, 1.0) * 100);
        }
    }

    /**
     * Abschlussmeldung eines erfolgreichen Laufs.
     *
     * @param dataAdded tatsaechlich uebertragene Menge nach Deduplizierung -- die
     *                  aussagekraeftige Zahl, nicht die Groesse der Quelle
     * @param snapshotId Kennung des erzeugten Snapshots
     */
    record Summary(
            Long filesNew,
            Long filesChanged,
            Long filesUnmodified,
            Long totalFilesProcessed,
            Long totalBytesProcessed,
            Long dataAdded,
            Double totalDurationSeconds,
            String snapshotId) implements ResticMessage {
    }

    /**
     * Ein Fehler waehrend des Laufs.
     *
     * <p>restic bricht nicht bei jedem Fehler ab: Eine unlesbare Datei wird gemeldet, der
     * Lauf geht weiter und endet mit einem Rueckgabewert ungleich null. Solche Meldungen
     * gehoeren ins Protokoll, damit nachvollziehbar bleibt, was fehlt.
     */
    record Failure(String message, String during, String item) implements ResticMessage {
    }
}
