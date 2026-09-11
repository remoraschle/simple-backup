package dev.remo.simplebackup.engine.docker;

import java.util.List;

/**
 * In welchem Betriebsmodus der Docker-Daemon laeuft.
 *
 * <p>Beide Modi werden unterstuetzt, aber sie unterscheiden sich in einem Punkt, der Nutzer
 * betrifft: Ohne Wurzelrechte kann kein Container ein Blockgeraet durchgereicht bekommen.
 * Statt den Betreiber nachts mit einem Rechtefehler zu ueberraschen, wird der Quelltyp
 * "Blockgeraet" im rootless-Betrieb gar nicht erst angeboten -- eine Funktion, die man nicht
 * anlegen kann, ist besser als eine, die beim ersten echten Lauf scheitert.
 *
 * @param rootless      true, wenn der Daemon ohne Wurzelrechte laeuft
 * @param serverVersion Version des Daemons, fuer Diagnosezwecke
 */
public record DockerRuntime(boolean rootless, String serverVersion) {

    /** Kennzeichen, mit dem der Daemon den rootless-Betrieb in seinen Sicherheitsoptionen meldet. */
    private static final String ROOTLESS_MARKER = "name=rootless";

    public static DockerRuntime from(DockerDto.SystemInfo info) {
        List<String> options = info.securityOptions() == null ? List.of() : info.securityOptions();
        boolean rootless = options.stream().anyMatch(option -> option.contains(ROOTLESS_MARKER));
        return new DockerRuntime(rootless, info.serverVersion());
    }

    /**
     * Ob Sicherungen ganzer Festplatten moeglich sind.
     *
     * <p>Ein Blockgeraet laesst sich nur mit Wurzelrechten durchreichen.
     */
    public boolean supportsBlockDevices() {
        return !rootless;
    }

    /** Erklaerung fuer die Oberflaeche, warum eine Funktion nicht zur Verfuegung steht. */
    public String blockDeviceUnavailableReason() {
        return supportsBlockDevices() ? null
                : "Der Docker-Daemon läuft ohne Wurzelrechte (rootless). In diesem Betrieb "
                        + "lässt sich kein Blockgerät an einen Container durchreichen. "
                        + "Für Festplatten-Abbilder ist ein Daemon mit Wurzelrechten nötig.";
    }
}
