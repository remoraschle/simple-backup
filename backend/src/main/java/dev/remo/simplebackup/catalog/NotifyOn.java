package dev.remo.simplebackup.catalog;

/** Wann fuer diesen Plan benachrichtigt wird. */
public enum NotifyOn {
    /** Nur bei Fehlschlag und Teilerfolg. Der Standard. */
    FAILURE,
    /** Auch bei Erfolg. */
    ALWAYS,
    /** Gar nicht. */
    NEVER
}
