package dev.remo.simplebackup.engine;

import java.util.List;

/**
 * Welche Blockgeraete gesichert werden duerfen -- und ob ueberhaupt.
 *
 * <p>Zwei Bedingungen muessen erfuellt sein, und beide werden hier beantwortet, damit die
 * Oberflaeche die Quelle gar nicht erst anbieten muss, wenn sie ohnehin scheitern wuerde:
 *
 * <ol>
 *   <li><b>Wurzelrechte.</b> Ein Daemon ohne sie kann kein Geraet in einen Container
 *       durchreichen. Das laesst sich nicht umgehen, nur feststellen.
 *   <li><b>Freigabe.</b> Das Geraet muss ausdruecklich in der Konfiguration stehen. Ohne
 *       diese Liste entschiede der Inhalt eines Formularfelds darueber, welche Platte
 *       roh gelesen wird.
 * </ol>
 *
 * <p>Durchgereicht wird nur lesend und nie ueber {@code privileged}: Ein Sicherungswerkzeug
 * hat auf einem Geraet nichts zu schreiben.
 */
public class DeviceAccess {

    private final boolean supported;
    private final String unsupportedReason;
    private final List<String> allowed;

    public DeviceAccess(boolean supported, String unsupportedReason, List<String> allowed) {
        this.supported = supported;
        this.unsupportedReason = unsupportedReason;
        this.allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    /** Ob Sicherungen ganzer Datentraeger ueberhaupt in Frage kommen. */
    public boolean available() {
        return supported && !allowed.isEmpty();
    }

    /** Die freigegebenen Geraete, damit die Oberflaeche sie zur Auswahl stellen kann. */
    public List<String> allowed() {
        return allowed;
    }

    /**
     * Warum es nicht geht, in ganzen Saetzen und mit dem naechsten Schritt darin.
     *
     * @return {@code null}, wenn es geht
     */
    public String unavailableReason() {
        if (!supported) {
            return unsupportedReason;
        }
        if (allowed.isEmpty()) {
            return """
                    Es ist kein Blockgeraet freigegeben. Geraete muessen unter \
                    simplebackup.engine.devices einzeln eingetragen und in der Compose-Datei \
                    unter "devices:" durchgereicht werden -- niemals ueber privileged.""";
        }
        return null;
    }

    /**
     * Prueft eine Angabe, bevor daraus ein Kommando wird.
     *
     * <p>Bewusst ein Vergleich gegen die Liste und keine Pruefung des Musters: Ein Muster
     * wie "alles unter /dev" liesse sich mit {@code /dev/../etc} umgehen, und ein Pfad, der
     * heute auf eine Platte zeigt, kann morgen ein Link sein.
     */
    public void require(String device) {
        String reason = unavailableReason();
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        if (!allowed.contains(device)) {
            throw new IllegalArgumentException(
                    "Das Geraet %s ist nicht freigegeben. Freigegeben sind: %s"
                            .formatted(device, String.join(", ", allowed)));
        }
    }
}
