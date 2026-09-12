package dev.remo.simplebackup.run;

/** Wodurch ein Lauf ausgeloest wurde. */
public enum RunTrigger {
    SCHEDULE,
    MANUAL,
    RETRY,
    API
}
