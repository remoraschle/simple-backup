package dev.remo.simplebackup.engine.docker;

/**
 * Labels, an denen dieses Werkzeug seine Container wiedererkennt.
 *
 * <p>Sie tragen zwei Dinge, die ohne sie nicht gingen: das Wiederanhaengen nach einem
 * Neustart des Backends und das Aufraeumen verwaister Container.
 */
public final class DockerLabels {

    /** Markiert jeden von dieser Anwendung erzeugten Container. */
    public static final String MANAGED_BY = "simple-backup.managed-by";

    public static final String MANAGED_BY_VALUE = "simple-backup";

    /** Verbindet den Container mit dem Schritt, zu dem er gehoert. */
    public static final String EXECUTION_ID = "simple-backup.execution-id";

    private DockerLabels() {
    }
}
