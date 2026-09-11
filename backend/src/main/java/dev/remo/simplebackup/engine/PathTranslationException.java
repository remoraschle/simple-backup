package dev.remo.simplebackup.engine;

/**
 * Ein Pfad liess sich keinem Host-Pfad zuordnen.
 *
 * <p>Bewusst ein Fehler und keine Schaetzung: Ein Backup, das ins Leere greift, muss beim
 * Anlegen scheitern und nicht nachts um drei.
 */
public class PathTranslationException extends RuntimeException {

    public PathTranslationException(String message) {
        super(message);
    }
}
