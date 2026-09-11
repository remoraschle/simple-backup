package dev.remo.simplebackup.engine;

import java.util.Optional;

/**
 * Fuehrt einen Backup-Schritt aus.
 *
 * <p>Im Betrieb ist das {@code DockerJobExecutor}, der je Schritt einen Container startet.
 * Fuer Tests und die lokale Entwicklung ohne Docker-Daemon gibt es
 * {@code LocalProcessExecutor}. Tests, die fuer jeden Fall einen Container hochziehen,
 * laufen zu langsam, um sie oft auszufuehren -- und Tests, die man selten ausfuehrt, findet
 * niemand nuetzlich.
 */
public interface BackupExecutor {

    /**
     * Startet den Schritt und kehrt sofort zurueck.
     *
     * @throws ExecutionException wenn der Schritt nicht gestartet werden konnte
     */
    RunningExecution start(ExecutionRequest request, LogSink logSink);

    /**
     * Sucht eine bereits laufende Ausfuehrung anhand der Kennung aus der Anfrage.
     *
     * <p>Das ist der Kern des Wiederanhaengens: Startet das Backend neu, laufen die
     * Runner-Container weiter. Ohne diese Methode waere ein vierstuendiger Lauf durch einen
     * Neustart verloren.
     *
     * @return die laufende Ausfuehrung, oder leer, wenn keine mehr existiert
     */
    Optional<RunningExecution> reattach(String executionId, LogSink logSink);
}
