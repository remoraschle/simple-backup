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

    /** Ob dieser Quelltyp bereits umgesetzt ist. */
    public boolean isImplemented() {
        return this == LOCAL_PATH || this == POSTGRES || this == GITHUB
                || this == S3 || this == SFTP;
    }
}
