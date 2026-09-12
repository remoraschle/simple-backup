package dev.remo.simplebackup.run;

import dev.remo.simplebackup.engine.BackupExecutor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bringt nach einem Neustart Ordnung in die vorgefundenen Laeufe.
 *
 * <p>Weil die Runner-Container den Neustart ueberleben, steht in der Datenbank noch
 * {@code RUNNING}, waehrend der Container laengst fertig sein kann -- oder noch arbeitet.
 *
 * <p><b>Stand der Umsetzung:</b> Verwaiste Container werden entfernt und haengengebliebene
 * Laeufe als gescheitert vermerkt, mit einer Meldung, die den Grund nennt. Das
 * Wiederanhaengen an einen noch laufenden Container ist in der Ausfuehrungsschicht
 * vorbereitet ({@code BackupExecutor#reattach}), aber hier noch nicht verdrahtet: Dafuer
 * muesste je Schritt die Ausfuehrungskennung gespeichert werden. Bis dahin wird ein solcher
 * Lauf ehrlich als abgebrochen gemeldet, statt einen Erfolg zu behaupten, den niemand
 * geprueft hat.
 */
@Component
class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final RunRepository runs;
    private final BackupExecutor executor;

    StartupRecovery(RunRepository runs, BackupExecutor executor) {
        this.runs = runs;
        this.executor = executor;
    }

    @Transactional
    void recoverInterruptedRuns() {
        List<BackupRun> interrupted = runs.findAllByStatusIn(List.of(RunStatus.QUEUED, RunStatus.RUNNING));

        if (interrupted.isEmpty()) {
            cleanUpContainers(Set.of());
            return;
        }
        log.warn("{} Laeufe waren beim Herunterfahren noch aktiv", interrupted.size());

        Set<String> knownExecutionIds = interrupted.stream()
                .flatMap(run -> run.getSteps().stream())
                .map(RunStep::getContainerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        cleanUpContainers(knownExecutionIds);

        for (BackupRun run : interrupted) {
            run.getSteps().stream()
                    .filter(step -> step.getStatus() == StepStatus.RUNNING)
                    .forEach(step -> step.finish(StepStatus.CANCELLED, null,
                            "Beim Neustart des Backends abgebrochen"));

            run.finish(RunStatus.CANCELLED, """
                    Der Lauf war beim Herunterfahren des Backends noch aktiv und wurde \
                    abgebrochen. Beim naechsten regulaeren Termin startet er erneut.""");
            runs.save(run);
        }
    }

    private void cleanUpContainers(Set<String> knownExecutionIds) {
        try {
            Set<String> stillRunning = executor.reapOrphans(knownExecutionIds);
            if (!stillRunning.isEmpty()) {
                log.info("{} Ausfuehrungen laufen noch", stillRunning.size());
            }
        } catch (RuntimeException e) {
            // Ohne erreichbare Ausfuehrungsumgebung -- etwa in der lokalen Entwicklung --
            // ist das kein Fehlerfall.
            log.debug("Aufraeumen uebersprungen: {}", e.getMessage());
        }
    }
}
