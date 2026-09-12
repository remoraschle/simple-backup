package dev.remo.simplebackup.catalog;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Typisierte Konfiguration eines Ziels.
 *
 * <p>Zugangsdaten stehen hier nur als Verweis auf einen Eintrag der verschluesselten
 * Ablage -- niemals als Klartext. Diese Konfiguration landet als JSONB in der Datenbank und
 * geht unveraendert an die API.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TargetConfig.LocalPath.class, name = "LOCAL_PATH"),
        @JsonSubTypes.Type(value = TargetConfig.S3.class, name = "S3")})
public sealed interface TargetConfig {

    TargetType type();

    /**
     * Verweis auf das Repository-Passwort in der verschluesselten Ablage.
     *
     * <p>Im Spiegel-Modus {@code null}, denn eine unverschluesselte Kopie hat kein Passwort.
     */
    UUID repositoryPasswordCredentialId();

    /** Ein Verzeichnis auf einer Platte oder einem eingehaengten Netzlaufwerk. */
    record LocalPath(
            @NotBlank String path,
            UUID repositoryPasswordCredentialId) implements TargetConfig {

        public LocalPath {
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("Der Pfad muss absolut sein: " + path);
            }
        }

        @Override
        public TargetType type() {
            return TargetType.LOCAL_PATH;
        }
    }

    /**
     * Ein Bucket bei AWS oder einem S3-kompatiblen Dienst.
     *
     * @param endpoint               vollstaendige Adresse mit Schema
     * @param credentialId           Verweis auf das Schluesselpaar in der verschluesselten Ablage
     */
    record S3(
            @NotBlank String endpoint,
            @NotBlank String bucket,
            String prefix,
            UUID credentialId,
            UUID repositoryPasswordCredentialId) implements TargetConfig {

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
                throw new IllegalArgumentException("Ein S3-Ziel braucht hinterlegte Zugangsdaten");
            }
        }

        @Override
        public TargetType type() {
            return TargetType.S3;
        }
    }
}
