package dev.remo.simplebackup.security;

/**
 * Zwei Rollen genuegen, solange niemand mehr braucht.
 *
 * <p>{@code VIEWER} darf lesen, aber nichts anlegen, aendern, ausloesen oder
 * wiederherstellen.
 */
public enum UserRole {
    ADMIN,
    VIEWER
}
