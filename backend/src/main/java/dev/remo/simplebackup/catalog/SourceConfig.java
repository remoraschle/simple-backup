package dev.remo.simplebackup.catalog;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Typisierte Konfiguration einer Quelle.
 *
 * <p>In der Datenbank liegt sie als JSONB. Ein neuer Quelltyp braucht damit keine
 * Schemaaenderung -- die Pruefung findet hier statt, beim Anlegen, und nicht erst beim ersten
 * naechtlichen Lauf.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SourceConfig.LocalPath.class, name = "LOCAL_PATH"),
        @JsonSubTypes.Type(value = SourceConfig.Postgres.class, name = "POSTGRES"),
        @JsonSubTypes.Type(value = SourceConfig.GitHub.class, name = "GITHUB"),
        @JsonSubTypes.Type(value = SourceConfig.S3.class, name = "S3"),
        @JsonSubTypes.Type(value = SourceConfig.Sftp.class, name = "SFTP"),
        @JsonSubTypes.Type(value = SourceConfig.BlockDevice.class, name = "BLOCK_DEVICE")})
public sealed interface SourceConfig {

    SourceType type();

    /**
     * Ein Verzeichnis oder Netzlaufwerk.
     *
     * @param paths         zu sichernde Pfade aus Sicht des Backend-Containers
     * @param excludes      Ausschlussmuster in der Schreibweise von restic
     * @param oneFileSystem ob an Dateisystemgrenzen haltgemacht wird -- verhindert, dass ein
     *                      unterhalb eingehaengtes Netzlaufwerk unbemerkt mitgesichert wird
     */
    record LocalPath(
            @NotEmpty(message = "Mindestens ein Pfad muss angegeben sein") List<String> paths,
            List<String> excludes,
            boolean oneFileSystem) implements SourceConfig {

        public LocalPath {
            paths = paths == null ? List.of() : List.copyOf(paths);
            excludes = excludes == null ? List.of() : List.copyOf(excludes);

            for (String path : paths) {
                if (path == null || !path.startsWith("/")) {
                    throw new IllegalArgumentException("Pfade muessen absolut sein: " + path);
                }
            }
        }

        @Override
        public SourceType type() {
            return SourceType.LOCAL_PATH;
        }
    }

    /**
     * Eine PostgreSQL-Datenbank.
     *
     * <p>Gesichert wird ein Dump, kein Dateiabbild: Die Dateien einer laufenden Datenbank zu
     * kopieren ergibt einen Stand, den niemand einspielen kann.
     *
     * @param majorVersion Hauptversion des Servers. Bestimmt, welches {@code pg_dump}
     *                     verwendet wird -- ein aelteres weigert sich, eine neuere Datenbank
     *                     zu lesen, und ein Dump aus dem falschen Werkzeug faellt erst beim
     *                     Einspielen auf.
     * @param databases    zu sichernde Datenbanken. Leer bedeutet alle ausser den Vorlagen.
     * @param includeGlobals ob Rollen und Tablespaces mitgesichert werden. Ohne sie laesst
     *                       sich ein Dump zwar einspielen, aber niemand darf hinterher
     *                       darauf zugreifen.
     * @param credentialId Verweis auf das Passwort in der verschluesselten Ablage
     */
    record Postgres(
            @NotBlank String host,
            int port,
            Integer majorVersion,
            List<String> databases,
            @NotBlank String username,
            UUID credentialId,
            boolean includeGlobals) implements SourceConfig {

        public Postgres {
            port = port <= 0 ? 5432 : port;
            majorVersion = majorVersion == null ? 18 : majorVersion;
            databases = databases == null ? List.of() : List.copyOf(databases);

            if (majorVersion < 9 || majorVersion > 99) {
                throw new IllegalArgumentException("Unplausible Hauptversion: " + majorVersion);
            }
            if (credentialId == null) {
                throw new IllegalArgumentException("Ohne hinterlegtes Passwort geht kein Dump");
            }
            for (String database : databases) {
                if (database == null || database.isBlank()) {
                    throw new IllegalArgumentException("Ein Datenbankname darf nicht leer sein");
                }
            }
        }

        @Override
        public SourceType type() {
            return SourceType.POSTGRES;
        }
    }

    /**
     * Repositories bei GitHub.
     *
     * <p>Gespiegelt statt ausgecheckt: Ein Mirror enthaelt alle Branches und Tags und laesst
     * sich ohne GitHub wieder auspacken. Dazu kommen die Metadaten -- Issues und Releases
     * liegen nicht im Git-Repository und waeren sonst verloren.
     *
     * @param owner        Benutzer oder Organisation
     * @param includeForks ob geforkte Repositories mitgesichert werden. Meist nicht: Ihr
     *                     Inhalt liegt anderswo ohnehin.
     * @param repositories nur diese Repositories, leer fuer alle des Eigentuemers
     * @param credentialId Verweis auf den Token in der verschluesselten Ablage
     */
    record GitHub(
            @NotBlank String owner,
            List<String> repositories,
            boolean includeForks,
            boolean includeMetadata,
            UUID credentialId) implements SourceConfig {

        public GitHub {
            repositories = repositories == null ? List.of() : List.copyOf(repositories);

            if (credentialId == null) {
                throw new IllegalArgumentException(
                        "Ohne Token kommt man auch an oeffentliche Repositories nur begrenzt heran");
            }
        }

        @Override
        public SourceType type() {
            return SourceType.GITHUB;
        }
    }

    /**
     * Ein S3-Bucket als Quelle.
     *
     * <p>Nicht zu verwechseln mit einem S3-<em>Ziel</em>: Hier liegen fremde Daten, die
     * gesichert werden sollen -- etwa die Ablage einer anderen Anwendung.
     *
     * @param prefix       nur dieser Pfad im Bucket, leer fuer alles
     * @param credentialId Verweis auf das Schluesselpaar in der verschluesselten Ablage
     */
    record S3(
            @NotBlank String endpoint,
            @NotBlank String bucket,
            String prefix,
            String region,
            UUID credentialId) implements SourceConfig {

        public S3 {
            if (endpoint == null || (!endpoint.startsWith("http://") && !endpoint.startsWith("https://"))) {
                throw new IllegalArgumentException(
                        "Die Adresse muss mit http:// oder https:// beginnen: " + endpoint);
            }
            if (bucket == null || bucket.isBlank() || bucket.contains("/")) {
                throw new IllegalArgumentException(
                        "Der Bucket-Name darf nicht leer sein und keinen Schraegstrich enthalten: " + bucket);
            }
            if (credentialId == null) {
                throw new IllegalArgumentException("Ohne Zugangsdaten kommt man an kein Bucket");
            }
            region = region == null || region.isBlank() ? "us-east-1" : region;
        }

        @Override
        public SourceType type() {
            return SourceType.S3;
        }
    }

    /**
     * Ein Verzeichnis auf einem SFTP-Server.
     *
     * @param hostKey      Der erwartete Hostschluessel, als Zeile im Format von
     *                     {@code known_hosts}. <b>Pflicht.</b> Ein Backup, das jeden
     *                     Serverschluessel akzeptiert, laedt seine Daten im Zweifel bei
     *                     jemand anderem hoch -- und merkt es nicht.
     * @param credentialId Verweis auf Passwort oder privaten Schluessel in der
     *                     verschluesselten Ablage; welcher es ist, sagt die Art des Zugangs
     */
    record Sftp(
            @NotBlank String host,
            int port,
            @NotBlank String username,
            @NotBlank String path,
            @NotBlank String hostKey,
            UUID credentialId) implements SourceConfig {

        public Sftp {
            port = port <= 0 ? 22 : port;

            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("Der Pfad muss absolut sein: " + path);
            }
            if (hostKey == null || hostKey.isBlank()) {
                throw new IllegalArgumentException("""
                        Ohne bekannten Hostschluessel wird nicht verbunden. Ein Backup, das jeden \
                        Schluessel akzeptiert, laedt seine Daten im Zweifel bei jemand anderem hoch.""");
            }
            if (credentialId == null) {
                throw new IllegalArgumentException("Ohne Zugangsdaten geht keine Verbindung");
            }
        }

        @Override
        public SourceType type() {
            return SourceType.SFTP;
        }
    }

    /**
     * Ein ganzer Datentraeger als Abbild.
     *
     * <p>Gelesen wird roh, Block fuer Block. Das ist die einzige Art, ein System zu sichern,
     * das sich nicht dateiweise erfassen laesst -- und zugleich die teuerste: Ein Abbild
     * dedupliziert restic schlecht, und es enthaelt auch den Teil der Platte, der frei ist.
     * Fuer "jede Nacht die ganze Platte" ist eine dateibasierte Quelle fast immer die
     * bessere Antwort; dieses Werkzeug kann beides und sagt es dazu.
     *
     * @param device     Pfad des Geraets, etwa {@code /dev/sdb}. Muss ausdruecklich
     *                   freigegeben sein -- siehe {@code simplebackup.engine.devices}.
     * @param imageName  Dateiname des Abbilds im Snapshot. Ein sprechender Name hilft
     *                   spaeter bei der Frage, welche Platte man da eigentlich vor sich hat.
     * @param sparse     ob Nullbloecke als Loecher geschrieben werden. Spart auf einer halb
     *                   leeren Platte ein Vielfaches, taugt aber nichts, wenn der freie
     *                   Bereich alte Daten enthaelt statt Nullen.
     */
    record BlockDevice(
            @NotBlank String device,
            String imageName,
            boolean sparse) implements SourceConfig {

        public BlockDevice {
            if (device == null || !device.startsWith("/dev/")) {
                throw new IllegalArgumentException(
                        "Ein Blockgeraet liegt unter /dev, angegeben war: " + device);
            }
            if (device.contains("..")) {
                throw new IllegalArgumentException("Der Geraetepfad darf keine Rueckspruenge enthalten");
            }
            imageName = imageName == null || imageName.isBlank()
                    ? device.substring(device.lastIndexOf('/') + 1) + ".img"
                    : imageName;

            if (imageName.contains("/")) {
                throw new IllegalArgumentException(
                        "Der Name des Abbilds ist ein Dateiname, kein Pfad: " + imageName);
            }
        }

        @Override
        public SourceType type() {
            return SourceType.BLOCK_DEVICE;
        }
    }
}
