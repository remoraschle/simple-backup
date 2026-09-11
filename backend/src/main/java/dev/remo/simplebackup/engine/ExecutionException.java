package dev.remo.simplebackup.engine;

/** Ein Schritt liess sich nicht starten oder nicht ueberwachen. */
public class ExecutionException extends RuntimeException {

    public ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
