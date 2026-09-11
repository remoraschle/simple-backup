package dev.remo.simplebackup.engine;

/**
 * Ein Verzeichnis oder benanntes Volume, das dem Runner zur Verfuegung steht.
 *
 * @param source       Host-Pfad oder Name eines Docker-Volumes. Bei einem Pfad ist es der
 *                     Pfad auf dem <em>Host</em>, nicht im Backend-Container -- der
 *                     Docker-Daemon loest Bind-Mounts gegen das Host-Dateisystem auf.
 * @param target       Pfad im Runner-Container
 * @param readOnly     true fuer Quellen. Das Werkzeug hat auf Originaldaten nichts zu
 *                     schreiben, und ein schreibgeschuetzter Mount macht einen Fehler
 *                     unmoeglich statt unwahrscheinlich.
 * @param namedVolume  true, wenn {@code source} ein Docker-Volume und kein Pfad ist
 */
public record VolumeMount(String source, String target, boolean readOnly, boolean namedVolume) {

    public VolumeMount {
        requireText(source, "source");
        requireText(target, "target");
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("Zielpfad muss absolut sein: " + target);
        }
        if (!namedVolume && !source.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Quellpfad muss ein absoluter Host-Pfad sein: " + source);
        }
    }

    /** Eine Quelle: immer schreibgeschuetzt. */
    public static VolumeMount readOnlyPath(String hostPath, String containerPath) {
        return new VolumeMount(hostPath, containerPath, true, false);
    }

    public static VolumeMount writablePath(String hostPath, String containerPath) {
        return new VolumeMount(hostPath, containerPath, false, false);
    }

    public static VolumeMount volume(String volumeName, String containerPath) {
        return new VolumeMount(volumeName, containerPath, false, true);
    }

    /** Schreibweise der Docker-API: {@code quelle:ziel:ro}. */
    public String toBindSpec() {
        return source + ":" + target + (readOnly ? ":ro" : "");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
    }
}
