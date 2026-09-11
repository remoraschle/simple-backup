package dev.remo.simplebackup.shared;

/** Ein angefordertes Objekt existiert nicht. Wird zu HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
