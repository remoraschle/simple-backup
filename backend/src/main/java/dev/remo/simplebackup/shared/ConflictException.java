package dev.remo.simplebackup.shared;

/** Die Anfrage widerspricht dem aktuellen Zustand. Wird zu HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
