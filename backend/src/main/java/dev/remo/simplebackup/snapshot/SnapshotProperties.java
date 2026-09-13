package dev.remo.simplebackup.snapshot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param restoreTimeout Zeitlimit einer Wiederherstellung. Grosszuegig: Im Ernstfall geht es
 *                       um viele Daten, und ein Abbruch mitten im Zurueckholen ist das
 *                       Letzte, was jemand braucht.
 * @param dumpTimeout    Zeitlimit fuer die Ausgabe einer einzelnen Datei
 * @param checkTimeout   Zeitlimit fuer die Pruefung des Repositories
 * @param verifyPercent  Anteil der Daten, den die regelmaessige Pruefung wirklich liest
 * @param stagingDirectory Arbeitsverzeichnis fuer einzelne zurueckgeholte Dateien. Muss
 *                         eingehaengt sein, sonst kommt der Runner nicht hin.
 */
@ConfigurationProperties(prefix = "simplebackup.snapshot")
public record SnapshotProperties(
        Duration restoreTimeout,
        Duration dumpTimeout,
        Duration checkTimeout,
        Integer verifyPercent,
        String stagingDirectory) {

    public SnapshotProperties {
        restoreTimeout = restoreTimeout == null ? Duration.ofHours(12) : restoreTimeout;
        dumpTimeout = dumpTimeout == null ? Duration.ofMinutes(10) : dumpTimeout;
        checkTimeout = checkTimeout == null ? Duration.ofHours(6) : checkTimeout;
        verifyPercent = verifyPercent == null ? 5 : Math.clamp(verifyPercent, 1, 100);
        stagingDirectory = stagingDirectory == null || stagingDirectory.isBlank()
                ? "/var/lib/simple-backup/staging" : stagingDirectory;
    }
}
