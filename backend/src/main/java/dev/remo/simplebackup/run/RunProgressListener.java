package dev.remo.simplebackup.run;

import dev.remo.simplebackup.restic.ResticMessage;
import java.util.List;
import java.util.UUID;

/**
 * Nimmt entgegen, was waehrend eines Laufs geschieht.
 *
 * <p>Trennt die Ausfuehrung vom Festhalten: {@link BackupRunner} kennt weder Datenbank noch
 * Logdatei und laesst sich dadurch ohne beides testen.
 */
public interface RunProgressListener {

    /** @return Kennung, unter der der Schritt spaeter wiedergefunden wird */
    UUID stepStarted(StepKind kind, UUID targetId, String description, String image,
            List<String> redactedCommand);

    void stepFinished(UUID stepId, StepStatus status, Integer exitCode, String message);

    /** Fortschritt eines laufenden Schritts, mehrfach je Sekunde moeglich. */
    void progress(UUID stepId, ResticMessage.Progress progress);

    /** Eine Ausgabezeile, bereits von Geheimnissen bereinigt. */
    void logLine(String line);
}
