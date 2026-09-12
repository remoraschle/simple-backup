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
     * Die Umgebung, in der Schritte standardmaessig laufen.
     *
     * <p>Der Aufrufer soll nicht wissen muessen, ob dahinter ein Container-Image oder etwas
     * anderes steht -- er baut damit nur seine Anfrage.
     */
    String defaultEnvironment();

    /**
     * Raeumt nach einem Neustart auf.
     *
     * <p>Wer ausfuehrt, raeumt auch auf: Der Aufrufer sagt nur, welche Schritte er noch
     * kennt; was davon uebrig ist und was entsorgt werden muss, weiss die Ausfuehrung.
     *
     * @param knownExecutionIds Schritte, die die Anwendung noch kennt
     * @return die Kennungen der Ausfuehrungen, die noch laufen
     */
    java.util.Set<String> reapOrphans(java.util.Set<String> knownExecutionIds);

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
