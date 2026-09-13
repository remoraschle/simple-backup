package dev.remo.simplebackup.catalog;

/** Muss mit dem CHECK-Constraint auf {@code backup_source.type} uebereinstimmen. */
public enum SourceType {
    LOCAL_PATH,
    POSTGRES,
    GITHUB,
    S3,
    SFTP,
    FTP,
    BLOCK_DEVICE;

    /**
     * Ob dieser Quelltyp bereits umgesetzt ist.
     *
     * <p>Nicht zu verwechseln mit "verfuegbar": Ein Blockgeraet ist umgesetzt, laesst sich
     * aber nur sichern, wo der Daemon Wurzelrechte hat und das Geraet freigegeben ist.
     */
    public boolean isImplemented() {
        return this != FTP;
    }
}
