package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.restic.ResticMessage;
import dev.remo.simplebackup.shared.NotFoundException;
import dev.remo.simplebackup.snapshot.SnapshotService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Startet Laeufe, begrenzt die Nebenlaeufigkeit und verteilt ihren Verlauf.
 *
 * <p>Die Ausfuehrung laeuft in einem eigenen Thread, nicht im aufrufenden: Ein Backup dauert
 * Minuten bis Stunden. Der Aufrufer -- Zeitplaner oder API -- bekommt sofort die Kennung des
 * Laufs zurueck.
 *
 * <p>Alles, was in die Datenbank geht, laeuft ueber {@link RunPersistence}. Der Umweg ist
 * kein Zierat: Spring legt {@code @Transactional} als Stellvertreter um die Bean, und ein
 * Aufruf innerhalb derselben Klasse ginge daran vorbei.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunRepository runs;
    private final RunPersistence persistence;
    private final RunEventPublisher events;
    private final CatalogService catalog;
    private final BackupRunner runner;
    private final RunNotifier notifier;
    private final SnapshotService snapshots;
    private final BackupMetrics metrics;
    private final RunProperties properties;

    /**
     * Begrenzt, wie viele Laeufe gleichzeitig arbeiten.
     *
     * <p>Ohne diese Grenze koennten nach einem Stillstand alle faelligen Plaene gleichzeitig
     * starten und den Server lahmlegen -- gerade dann, wenn er ohnehin erst wieder hochgekommen
     * ist.
     */
    private final Semaphore parallelRuns;

    /** Laufende Ausfuehrungen, damit sie sich abbrechen lassen. */
    private final Map<UUID, Thread> activeRuns = new ConcurrentHashMap<>();

    RunService(RunRepository runs, RunPersistence persistence, RunEventPublisher events,
            CatalogService catalog, BackupRunner runner, RunNotifier notifier,
            SnapshotService snapshots, BackupMetrics metrics, RunProperties properties) {
        this.runs = runs;
        this.persistence = persistence;
        this.events = events;
        this.catalog = catalog;
        this.runner = runner;
        this.notifier = notifier;
        this.snapshots = snapshots;
        this.metrics = metrics;
        this.properties = properties;
        this.parallelRuns = new Semaphore(properties.maxParallelRuns());
    }

    /**
     * Stellt einen Lauf in die Warteschlange und startet ihn.
     *
     * @return Kennung des Laufs, oder leer, wenn fuer diesen Plan bereits einer laeuft
     */
    public Optional<UUID> startRun(UUID planId, RunTrigger trigger) {
        if (persistence.hasActiveRun(planId)) {
            // Zwei Laeufe desselben Plans wuerden sich am selben Repository gegenseitig
            // aussperren; restic laesst nur einen Schreiber zu.
            log.info("Plan {} laeuft bereits, kein zweiter Lauf gestartet", planId);
            return Optional.empty();
        }

        BackupRun run = persistence.create(planId, trigger);
        UUID runId = run.getId();

        Thread worker = Thread.ofVirtual()
                .name("backup-run-" + runId)
                .start(() -> executeRun(runId, planId));

        activeRuns.put(runId, worker);
        return Optional.of(runId);
    }

    /** Bricht einen laufenden Lauf ab. */
    public void cancelRun(UUID runId) {
        Thread worker = activeRuns.get(runId);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void executeRun(UUID runId, UUID planId) {
        boolean acquired = false;
        try {
            acquired = parallelRuns.tryAcquire(1, TimeUnit.HOURS);
            if (!acquired) {
                finishWithError(runId, planId, RunStatus.FAILED,
                        "Kein freier Platz fuer weitere gleichzeitige Laeufe");
                return;
            }

            ExecutablePlan plan = catalog.toExecutable(planId);

            try (var logWriter = new RunLogWriter(Path.of(properties.logDirectory()), runId)) {
                persistence.markRunning(runId, logWriter.path());
                logWriter.append("Plan: %s".formatted(plan.planName()));

                List<BackupRunner.TargetOutcome> outcomes =
                        runner.run(plan, new PersistingListener(runId, logWriter));

                RunPersistence.Outcome outcome = persistence.complete(runId, outcomes);

                recordSnapshots(runId, planId, outcomes, outcome.finishedAt());
                catalog.recordRunResult(planId, outcome.finishedAt(), outcome.status().name());
                events.publish(runId, new RunEvent.Finished(outcome.status(), outcome.errorSummary()));
                events.closeStream(runId);
                notifier.runFinished(planId, runId, outcome.status(), outcome.errorSummary(), outcomes);
                metrics.runFinished(planId, outcome.status());

                log.info("Lauf {} beendet: {}", runId, outcome.status());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishWithError(runId, planId, RunStatus.CANCELLED, "Abgebrochen");
        } catch (RuntimeException e) {
            log.error("Lauf {} unerwartet gescheitert", runId, e);
            finishWithError(runId, planId, RunStatus.FAILED, String.valueOf(e.getMessage()));
        } finally {
            if (acquired) {
                parallelRuns.release();
            }
            activeRuns.remove(runId);
        }
    }

    /**
     * Traegt ein, was der Lauf im Repository hinterlassen hat.
     *
     * <p>Fehler hier duerfen den Lauf nicht kippen: Die Sicherung ist geschrieben, und ein
     * fehlender Eintrag im Verzeichnis aendert daran nichts -- er laesst sich aus dem
     * Repository jederzeit wieder herstellen.
     */
    private void recordSnapshots(UUID runId, UUID planId, List<BackupRunner.TargetOutcome> outcomes,
            java.time.Instant finishedAt) {

        for (BackupRunner.TargetOutcome outcome : outcomes) {
            if (!outcome.successful() || outcome.summary() == null) {
                continue;
            }
            try {
                snapshots.record(runId, planId, outcome.targetId(), outcome.summary().snapshotId(),
                        outcome.summary().totalBytesProcessed(), finishedAt);
            } catch (RuntimeException e) {
                log.warn("Snapshot des Ziels {} liess sich nicht vermerken", outcome.targetName(), e);
            }
        }
    }

    /**
     * Beendet einen Lauf, der nicht bis zu den Zielen gekommen ist.
     *
     * <p>Auch dieser Fall wird gemeldet -- gerade dieser: Ein Lauf, der schon am Laden des
     * Plans scheitert, sichert nichts und faellt sonst niemandem auf.
     */
    private void finishWithError(UUID runId, UUID planId, RunStatus status, String message) {
        persistence.finish(runId, status, message);
        events.publish(runId, new RunEvent.Finished(status, message));
        events.closeStream(runId);
        notifier.runFinished(planId, runId, status, message, List.of());
        metrics.runFinished(planId, status);
    }

    // ------------------------------------------------------------------ Abfrage

    @Transactional(readOnly = true)
    public Page<BackupRun> findRuns(UUID planId, Pageable pageable) {
        return planId == null
                ? runs.findAllByOrderByQueuedAtDesc(pageable)
                : runs.findAllByPlanIdOrderByQueuedAtDesc(planId, pageable);
    }

    public BackupRun findRun(UUID runId) {
        return persistence.findWithSteps(runId);
    }

    @Transactional(readOnly = true)
    public String readLog(UUID runId) {
        BackupRun run = runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("Lauf %s nicht gefunden".formatted(runId)));

        return run.getLogPath() == null ? "" : RunLogWriter.read(Path.of(run.getLogPath()));
    }

    /**
     * Meldet einen Zuschauer fuer die Ausgabe eines laufenden Backups an.
     *
     * <p>Ist der Lauf bereits beendet, wird der Datenstrom sofort geschlossen -- die
     * Oberflaeche holt sich das Protokoll dann als Ganzes.
     */
    public SseEmitter streamEvents(UUID runId) {
        BackupRun run = findRun(runId);
        SseEmitter emitter = events.subscribe(runId, java.time.Duration.ofHours(12).toMillis());

        if (run.getStatus().isFinished()) {
            events.publish(runId, new RunEvent.Finished(run.getStatus(), run.getErrorSummary()));
            events.closeStream(runId);
        }
        return emitter;
    }

    /**
     * Haelt fest, was der Runner meldet, und verteilt es an die Oberflaeche.
     *
     * <p>Fortschrittsmeldungen kommen mehrfach je Sekunde und werden bewusst nicht
     * gespeichert -- sie gehen nur ins Protokoll und an die Zuschauer. Jede davon in die
     * Datenbank zu schreiben, waere Schreiblast ohne Nutzen.
     */
    private final class PersistingListener implements RunProgressListener {

        private final UUID runId;
        private final RunLogWriter logWriter;

        private PersistingListener(UUID runId, RunLogWriter logWriter) {
            this.runId = runId;
            this.logWriter = logWriter;
        }

        @Override
        public UUID stepStarted(StepKind kind, UUID targetId, String description, String image,
                List<String> redactedCommand) {
            logWriter.appendSection(description);
            logWriter.append("$ " + String.join(" ", redactedCommand));

            UUID stepId = persistence.addStep(runId, kind, targetId, description, image, redactedCommand);
            events.publish(runId, new RunEvent.Step(stepId, kind, StepStatus.RUNNING, description));
            return stepId;
        }

        @Override
        public void stepFinished(UUID stepId, StepStatus status, Integer exitCode, String message) {
            logWriter.append("→ %s%s".formatted(status,
                    exitCode == null ? "" : " (Rueckgabewert %d)".formatted(exitCode)));

            persistence.finishStep(stepId, status, exitCode, message);
            events.publish(runId, new RunEvent.Step(stepId, null, status, message));
        }

        @Override
        public void progress(UUID stepId, ResticMessage.Progress progress) {
            events.publish(runId, new RunEvent.Progress(stepId, progress.percent(),
                    progress.filesDone(), progress.totalFiles(), progress.bytesDone(),
                    progress.totalBytes(), progress.secondsRemaining()));
        }

        @Override
        public void logLine(String line) {
            logWriter.append(line);
            events.publish(runId, new RunEvent.Log(line));
        }
    }
}
