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
 */
@ConfigurationProperties(prefix = "simplebackup.run")
public record RunProperties(
        String logDirectory,
        Duration logRetention,
        int maxParallelRuns,
        Duration schedulerPollInterval,
        String stagingDirectory) {

    public RunProperties {
        logDirectory = orDefault(logDirectory, "/var/lib/simple-backup/logs");
        stagingDirectory = orDefault(stagingDirectory, "/var/lib/simple-backup/staging");
        logRetention = logRetention == null ? Duration.ofDays(90) : logRetention;
        schedulerPollInterval = schedulerPollInterval == null ? Duration.ofSeconds(30) : schedulerPollInterval;
        maxParallelRuns = maxParallelRuns <= 0 ? 2 : maxParallelRuns;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
