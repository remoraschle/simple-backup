package dev.remo.simplebackup.engine.docker;

/** Die Docker-API hat einen Fehler gemeldet oder war nicht erreichbar. */
public class DockerApiException extends RuntimeException {

    private final int statusCode;

    public DockerApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public DockerApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP-Status, oder -1 wenn die Anfrage nicht zustande kam. */
    public int statusCode() {
        return statusCode;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    /**
     * Ob der Socket-Proxy die Anfrage abgewiesen hat.
     *
     * <p>Der haeufigste Konfigurationsfehler im Betrieb: Ein Endpunkt wird gebraucht, ist im
     * Proxy aber nicht freigegeben. Die Meldung soll darauf hinweisen statt nur "403".
     */
    public boolean isForbiddenByProxy() {
        return statusCode == 403;
    }
}
