package dev.remo.simplebackup.catalog;

/** Muss mit dem CHECK-Constraint auf {@code backup_target.type} uebereinstimmen. */
public enum TargetType {
    LOCAL_PATH,
    S3,
    SFTP;

    public boolean isImplemented() {
        return this == LOCAL_PATH || this == S3;
    }
}
