package dev.remo.simplebackup.engine;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param executor Wie ein Schritt ausgefuehrt wird: {@code docker} im Betrieb,
 *                 {@code local} als Kindprozess fuer die Entwicklung
 * @param devices  Blockgeraete, die gesichert werden duerfen, als vollstaendige Pfade.
 *
 *                 <p><b>Standardmaessig leer, und das mit Absicht.</b> Ein Blockgeraet
 *                 durchzureichen heisst, den rohen Inhalt einer Platte lesbar zu machen --
 *                 einschliesslich aller Dateien, an die sonst niemand herankaeme. Wer das
 *                 will, sagt es ausdruecklich und nennt die Geraete einzeln. Eine
 *                 Anwendung, die von sich aus {@code /dev/sda} lesen darf, weil jemand
 *                 einen Pfad in ein Formular getippt hat, waere kein Sicherungswerkzeug,
 *                 sondern eine Hintertuer.
 */
@ConfigurationProperties(prefix = "simplebackup.engine")
public record EngineProperties(String executor, List<String> devices) {

    public EngineProperties {
        executor = executor == null || executor.isBlank() ? "docker" : executor;
        devices = devices == null ? List.of() : List.copyOf(devices);
    }
}
