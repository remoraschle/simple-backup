package dev.remo.simplebackup.catalog;

/**
 * Wie auf ein Ziel geschrieben wird.
 */
public enum TargetMode {

    /**
     * Versionierte, verschluesselte und deduplizierte Sicherung mit restic.
     *
     * <p>Nur in diesem Modus gibt es Snapshots, eine Aufbewahrungsregel und einen
     * Wiederherstellungs-Browser.
     */
    RESTIC,

    /**
     * Unverschluesselte, nicht versionierte 1:1-Kopie mit rsync.
     *
     * <p>Kein Kompromiss, sondern ein eigener Anwendungsfall: die Kopie auf der
     * USB-Festplatte, die sich ohne jedes Werkzeug im Dateimanager oeffnen laesst.
     */
    MIRROR;

    /** Ob dieser Modus Snapshots und damit Aufbewahrungsregeln kennt. */
    public boolean supportsSnapshots() {
        return this == RESTIC;
    }
}
