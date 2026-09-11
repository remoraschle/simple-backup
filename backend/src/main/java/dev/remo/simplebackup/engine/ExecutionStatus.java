package dev.remo.simplebackup.engine;

public enum ExecutionStatus {
    /** Beendet mit Rueckgabewert 0. */
    SUCCESS,
    /** Beendet mit einem Rueckgabewert ungleich 0. */
    FAILED,
    /** Zeitlimit ueberschritten und daraufhin abgebrochen. */
    TIMEOUT,
    /** Auf Wunsch abgebrochen. */
    CANCELLED;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
