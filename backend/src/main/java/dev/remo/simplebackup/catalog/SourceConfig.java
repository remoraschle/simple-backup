package dev.remo.simplebackup.catalog;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Typisierte Konfiguration einer Quelle.
 *
 * <p>In der Datenbank liegt sie als JSONB. Ein neuer Quelltyp braucht damit keine
 * Schemaaenderung -- die Pruefung findet hier statt, beim Anlegen, und nicht erst beim ersten
 * naechtlichen Lauf.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = SourceConfig.LocalPath.class, name = "LOCAL_PATH")})
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
}
