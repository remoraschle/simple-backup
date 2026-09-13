package dev.remo.simplebackup.notification;

/**
 * Dringlichkeit einer Meldung.
 *
 * <p>Jeder Kanal hat eine Mindeststufe. Wer den Erfolgsfall nicht sehen will, stellt sie auf
 * {@code WARNING} -- und bekommt trotzdem alles, was schiefgeht.
 */
public enum Severity {
    /** Alles in Ordnung, rein informativ. */
    INFO,
    /** Etwas ist schiefgegangen, aber nicht alles -- etwa ein Teilerfolg. */
    WARNING,
    /** Es wurde nichts gesichert, oder eine Sicherung ist ganz ausgeblieben. */
    CRITICAL;

    boolean reaches(Severity minimum) {
        return ordinal() >= minimum.ordinal();
    }
}
