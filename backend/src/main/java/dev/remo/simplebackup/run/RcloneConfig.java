package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.SourceConfig;
import java.util.List;

/**
 * Baut die Konfigurationsdatei fuer rclone.
 *
 * <p>Die gesamte Datei ist ein Geheimnis und geht als solche in den Runner: Sie enthaelt
 * Schluessel und Passwoerter. Ueber die Kommandozeile oder die Umgebung waere beides
 * dauerhaft lesbar -- in der Prozessliste beziehungsweise ueber {@code docker inspect}.
 *
 * <p>Der Abschnittsname ist immer derselbe, weil je Lauf genau eine Gegenstelle gebraucht
 * wird. Das erspart es, einen Namen aus Benutzereingaben zu bilden -- und damit die Frage,
 * was passiert, wenn jemand eine eckige Klammer in den Namen schreibt.
 */
final class RcloneConfig {

    /** Name der Gegenstelle in der Konfiguration und auf der Kommandozeile. */
    static final String REMOTE = "quelle";

    /** Dateiname der Konfiguration im Runner. */
    static final String CONFIG_FILE = "rclone.conf";

    /** Dateiname der bekannten Hostschluessel im Runner. */
    static final String KNOWN_HOSTS_FILE = "known_hosts";

    private RcloneConfig() {
    }

    /**
     * @param accessKeyId     Schluessel des S3-Zugangs
     * @param secretAccessKey Geheimnis des S3-Zugangs
     */
    static String forS3(SourceConfig.S3 source, String accessKeyId, String secretAccessKey) {
        return """
                [%s]
                type = s3
                provider = Other
                env_auth = false
                access_key_id = %s
                secret_access_key = %s
                endpoint = %s
                region = %s
                """.formatted(REMOTE, accessKeyId, secretAccessKey, source.endpoint(), source.region());
    }

    /**
     * SFTP mit privatem Schluessel.
     *
     * <p><b>Nur mit Schluessel, nicht mit Passwort.</b> Fuer ein Konto, das jede Nacht
     * unbeaufsichtigt Daten holt, ist ein Schluessel ohnehin die richtige Wahl. Ein Passwort
     * muesste ausserdem in der verschleierten Form von rclone abgelegt werden -- ein
     * Verfahren nachzubauen, dessen Schluessel man nicht pruefen kann, waere die schlechtere
     * Loesung als diese Einschraenkung.
     *
     * <p>Der Schluessel selbst geht als eigene Geheimnisdatei mit.
     */
    static String forSftpWithKey(SourceConfig.Sftp source, String keyFileName) {
        return """
                [%s]
                type = sftp
                host = %s
                port = %d
                user = %s
                key_file = %s
                known_hosts_file = %s
                """.formatted(REMOTE, source.host(), source.port(), source.username(),
                secretPath(keyFileName), secretPath(KNOWN_HOSTS_FILE));
    }

    /**
     * Das Kommando, das die Daten holt.
     *
     * <p>{@code copy} und nicht {@code sync}: Gesichert wird in ein frisches
     * Arbeitsverzeichnis, da gibt es nichts abzugleichen -- und {@code sync} loescht am Ziel,
     * was an der Quelle fehlt. Bei einem Backup ist das die falsche Richtung.
     */
    static List<String> copyCommand(String remotePath, String targetDirectory) {
        return List.of("rclone",
                "--config", secretPath(CONFIG_FILE),
                "copy",
                REMOTE + ":" + remotePath,
                targetDirectory,
                "--stats-one-line",
                "--stats", "30s",
                "--transfers", "8",
                "--retries", "3");
    }

    static String s3Path(SourceConfig.S3 source) {
        String prefix = source.prefix() == null ? "" : source.prefix().strip();
        prefix = prefix.startsWith("/") ? prefix.substring(1) : prefix;

        return prefix.isEmpty() ? source.bucket() : source.bucket() + "/" + prefix;
    }

    private static String secretPath(String fileName) {
        return dev.remo.simplebackup.engine.ExecutionRequest.SECRETS_DIRECTORY + "/" + fileName;
    }
}
