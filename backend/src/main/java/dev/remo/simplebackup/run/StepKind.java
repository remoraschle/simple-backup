package dev.remo.simplebackup.run;

/** Muss mit dem CHECK-Constraint auf {@code run_step.kind} uebereinstimmen. */
public enum StepKind {
    /** Vorbereiten des Ziels, etwa Anlegen des Repositories. */
    PREPARE,
    /** Beschaffen der Quelldaten, etwa ein Datenbank-Abzug. */
    ACQUIRE,
    /** Uebertragen auf ein Ziel. */
    TRANSFER,
    /** Pruefen des Ergebnisses. */
    VERIFY,
    /** Anwenden der Aufbewahrungsregel. */
    PRUNE,
    /** Aufraeumen von Zwischenstaenden. */
    CLEANUP
}
