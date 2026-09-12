package dev.remo.simplebackup.catalog;

/** Was geschieht, wenn ein Lauf ausgefallen ist, weil der Server aus war. */
public enum MissedRunPolicy {

    /**
     * Der verpasste Lauf entfaellt; es wird auf den naechsten regulaeren Termin gewartet.
     *
     * <p>Der Standard: Nach einem laengeren Ausfall wuerden sonst alle verpassten Laeufe auf
     * einmal starten und den Server lahmlegen.
     */
    SKIP,

    /** Ein verpasster Lauf wird beim naechsten Durchgang nachgeholt. */
    CATCH_UP
}
