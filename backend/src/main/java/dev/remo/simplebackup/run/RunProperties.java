package dev.remo.simplebackup.run;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param logDirectory        Wohin die Logdateien geschrieben werden. Nur der Pfad steht in
 *                            der Datenbank -- Logs gehoeren nicht hinein.
 * @param logRetention        Wie lange Logdateien aufbewahrt werden
 * @param maxParallelRuns     Wie viele Laeufe gleichzeitig laufen duerfen
 * @param schedulerPollInterval Wie oft nach faelligen Plaenen gesucht wird
 * @param stagingDirectory    Arbeitsverzeichnis fuer Zwischenstaende
 * @param pruneTimeout        Zeitlimit fuers Aufraeumen. Getrennt vom Zeitlimit des Plans,
 *                            weil {@code prune} das halbe Repository liest und deutlich
 *                            laenger braucht als eine Sicherung, die nur Aenderungen schreibt.
 * @param watchdogInterval    Wie oft auf ausgebliebene Sicherungen geprueft wird
 * @param watchdogGrace       Nachsicht, bevor eine Sicherung als ausgeblieben gilt. Ohne sie
 *                            schluege der Waechter bei einem Lauf an, der sich um Minuten
 *                            verspaetet -- und wer stuendlich falschen Alarm bekommt, schaltet
 *                            ihn ab.
 */
@ConfigurationProperties(prefix = "simplebackup.run")
public record RunProperties(
        String logDirectory,
        Duration logRetention,
        int maxParallelRuns,
        Duration schedulerPollInterval,
        String stagingDirectory,
        Duration pruneTimeout,
        Duration watchdogInterval,
        Duration watchdogGrace) {

    public RunProperties {
        logDirectory = orDefault(logDirectory, "/var/lib/simple-backup/logs");
        stagingDirectory = orDefault(stagingDirectory, "/var/lib/simple-backup/staging");
        logRetention = logRetention == null ? Duration.ofDays(90) : logRetention;
        schedulerPollInterval = schedulerPollInterval == null ? Duration.ofSeconds(30) : schedulerPollInterval;
        maxParallelRuns = maxParallelRuns <= 0 ? 2 : maxParallelRuns;
        pruneTimeout = pruneTimeout == null ? Duration.ofHours(4) : pruneTimeout;
        watchdogInterval = watchdogInterval == null ? Duration.ofMinutes(15) : watchdogInterval;
        watchdogGrace = watchdogGrace == null ? Duration.ofMinutes(30) : watchdogGrace;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
