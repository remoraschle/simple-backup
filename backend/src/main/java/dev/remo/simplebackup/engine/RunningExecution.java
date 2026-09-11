package dev.remo.simplebackup.engine;

import java.time.Duration;

/**
 * Ein laufender Schritt.
 *
 * <p>Die Kennung {@link #id()} ist der Griff, an dem der Schritt einen Neustart des Backends
 * ueberlebt: Sie wird gespeichert, und beim Hochfahren sucht
 * {@link BackupExecutor#reattach} die noch laufende Ausfuehrung darueber wieder.
 */
public interface RunningExecution {

    /** Kennung bei der ausfuehrenden Instanz -- Container-ID oder Prozesskennung. */
    String id();

    /** Kennung des Schritts aus der Anfrage. */
    String executionId();

    /**
     * Wartet auf das Ende.
     *
     * <p>Laeuft der Schritt laenger als das Zeitlimit, wird er abgebrochen und das Ergebnis
     * traegt {@link ExecutionStatus#TIMEOUT}. Ein haengender Schritt darf einen Plan nicht
     * dauerhaft blockieren.
     */
    ExecutionResult awaitCompletion(Duration timeout);

    /**
     * Bricht ab: erst freundlich, nach kurzer Frist mit Gewalt.
     *
     * <p>Darf mehrfach aufgerufen werden und auf einen bereits beendeten Schritt.
     */
    void cancel();
}
