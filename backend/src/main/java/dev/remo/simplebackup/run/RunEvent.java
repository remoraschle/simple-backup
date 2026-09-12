package dev.remo.simplebackup.run;

import java.util.UUID;

/**
 * Was waehrend eines Laufs an die Oberflaeche gemeldet wird.
 *
 * <p>Versiegelt, damit jede Ereignisart einen eigenen Namen im Datenstrom bekommt und die
 * Oberflaeche sie unterscheiden kann, ohne ein Feld auswerten zu muessen.
 */
public sealed interface RunEvent {

    /** Name des Ereignisses im Datenstrom. */
    String eventName();

    /** Eine Ausgabezeile, bereits von Geheimnissen bereinigt. */
    record Log(String line) implements RunEvent {
        @Override
        public String eventName() {
            return "log";
        }
    }

    /**
     * Fortschritt eines laufenden Schritts.
     *
     * @param secondsRemaining geschaetzte Restzeit, {@code null} solange restic nicht schaetzt
     */
    record Progress(
            UUID stepId,
            int percent,
            Long filesDone,
            Long totalFiles,
            Long bytesDone,
            Long totalBytes,
            Long secondsRemaining) implements RunEvent {

        @Override
        public String eventName() {
            return "progress";
        }
    }

    /** Ein Schritt hat begonnen oder geendet. */
    record Step(UUID stepId, StepKind kind, StepStatus status, String description)
            implements RunEvent {
        @Override
        public String eventName() {
            return "step";
        }
    }

    /** Der Lauf ist beendet. Danach wird der Datenstrom geschlossen. */
    record Finished(RunStatus status, String errorSummary) implements RunEvent {
        @Override
        public String eventName() {
            return "finished";
        }
    }
}
