package dev.remo.simplebackup.run;

/** Muss mit dem CHECK-Constraint auf {@code backup_run.status} uebereinstimmen. */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    /** Mindestens ein Ziel erfolgreich, mindestens eines gescheitert. */
    PARTIAL,
    FAILED,
    CANCELLED,
    TIMEOUT;

    public boolean isFinished() {
        return this != QUEUED && this != RUNNING;
    }

    /** Ob der Lauf Anlass zur Benachrichtigung gibt. */
    public boolean isProblem() {
        return this == PARTIAL || this == FAILED || this == TIMEOUT;
    }
}
