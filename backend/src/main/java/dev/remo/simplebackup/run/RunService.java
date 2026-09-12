package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.restic.ResticMessage;
import dev.remo.simplebackup.shared.NotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

/**
 * Startet Laeufe, haelt ihren Verlauf fest und begrenzt die Nebenlaeufigkeit.
 *
 * <p>Die Ausfuehrung laeuft in einem eigenen Thread, nicht im aufrufenden: Ein Backup dauert
 * Minuten bis Stunden. Der Aufrufer -- Scheduler oder API -- bekommt sofort die Kennung des
 * Laufs zurueck.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final List<RunStatus> ACTIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING);

    private final RunRepository runs;
    private final RunStepRepository steps;
    private final CatalogService catalog;
    private final BackupRunner runner;
    private final RunProperties properties;

    /**
     * Begrenzt, wie viele Laeufe gleichzeitig arbeiten.
     *
     * <p>Ohne diese Grenze koennten nach einem Stillstand alle faelligen Plaene gleichzeitig
     * starten und den Server lahmlegen -- gerade dann, wenn er ohnehin gerade erst wieder
     * laeuft.
     */
    private final Semaphore parallelRuns;

    /** Laufende Ausfuehrungen, damit sie sich abbrechen lassen. */
    private final Map<UUID, Thread> activeRuns = new ConcurrentHashMap<>();

    RunService(RunRepository runs, RunStepRepository steps, CatalogService catalog, BackupRunner runner,
            RunProperties properties) {
        this.runs = runs;
        this.steps = steps;
        this.catalog = catalog;
        this.runner = runner;
        this.properties = properties;
        this.parallelRuns = new Semaphore(properties.maxParallelRuns());
    }

    /**
     * Stellt einen Lauf in die Warteschlange und startet ihn.
     *
     * @return Kennung des Laufs, oder leer, wenn fuer diesen Plan bereits einer laeuft
     */
    public java.util.Optional<UUID> startRun(UUID planId, RunTrigger trigger) {
        if (runs.existsByPlanIdAndStatusIn(planId, ACTIVE)) {
            // Zwei Laeufe desselben Plans wuerden sich am selben Repository gegenseitig
            // aussperren; restic laesst nur einen Schreiber zu.
            log.info("Plan {} laeuft bereits, kein zweiter Lauf gestartet", planId);
            return java.util.Optional.empty();
        }

        BackupRun run = createRun(planId, trigger);
        Thread worker = Thread.ofVirtual()
                .name("backup-run-" + run.getId())
                .start(() -> executeRun(run.getId(), planId));

        activeRuns.put(run.getId(), worker);
        return java.util.Optional.of(run.getId());
    }

    @Transactional
    BackupRun createRun(UUID planId, RunTrigger trigger) {
        return runs.save(new BackupRun(planId, trigger, 1));
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
                finishRun(runId, RunStatus.FAILED, "Kein freier Platz fuer weitere gleichzeitige Laeufe");
                return;
            }

            ExecutablePlan plan = catalog.toExecutable(planId);

            try (var logWriter = new RunLogWriter(Path.of(properties.logDirectory()), runId)) {
                markRunning(runId, logWriter.path());
                logWriter.append("Plan: %s".formatted(plan.planName()));

                var listener = new PersistingListener(runId, logWriter);
                List<BackupRunner.TargetOutcome> outcomes = runner.run(plan, listener);

                completeRun(runId, planId, outcomes);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishRun(runId, RunStatus.CANCELLED, "Abgebrochen");
        } catch (RuntimeException e) {
            log.error("Lauf {} unerwartet gescheitert", runId, e);
            finishRun(runId, RunStatus.FAILED, String.valueOf(e.getMessage()));
        } finally {
            if (acquired) {
                parallelRuns.release();
            }
            activeRuns.remove(runId);
        }
    }

    @Transactional
    void markRunning(UUID runId, String logPath) {
        BackupRun run = require(runId);
        run.markRunning();
        run.setLogPath(logPath);
        runs.save(run);
    }

    @Transactional
    void completeRun(UUID runId, UUID planId, List<BackupRunner.TargetOutcome> outcomes) {
        BackupRun run = require(runId);

        // Kennzahlen aus der ersten erfolgreichen Abschlussmeldung: Die Quelle wird einmal
        // gelesen, die Zahlen sind fuer alle Ziele dieselben.
        outcomes.stream()
                .map(BackupRunner.TargetOutcome::summary)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(summary -> run.recordMetrics(summary.totalBytesProcessed(), summary.dataAdded(),
                        summary.filesNew(), summary.filesChanged(), summary.filesUnmodified()));

        RunStatus status = run.deriveStatus();
        run.finish(status, summariseFailures(outcomes));
        runs.save(run);

        catalog.recordRunResult(planId, run.getFinishedAt(), status.name());
        log.info("Lauf {} beendet: {}", runId, status);
    }

    /** Fasst zusammen, was schiefging -- kurz genug fuer eine Uebersicht. */
    private static String summariseFailures(List<BackupRunner.TargetOutcome> outcomes) {
        List<String> failures = outcomes.stream()
                .filter(outcome -> !outcome.successful())
                .map(outcome -> "%s: %s".formatted(outcome.targetName(), outcome.message()))
                .toList();

        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    @Transactional
    void finishRun(UUID runId, RunStatus status, String message) {
        runs.findById(runId).ifPresent(run -> {
            run.finish(status, message);
            runs.save(run);
        });
    }

    // ------------------------------------------------------------------ Abfrage

    @Transactional(readOnly = true)
    public Page<BackupRun> findRuns(UUID planId, Pageable pageable) {
        return planId == null
                ? runs.findAllByOrderByQueuedAtDesc(pageable)
                : runs.findAllByPlanIdOrderByQueuedAtDesc(planId, pageable);
    }

    @Transactional(readOnly = true)
    public BackupRun findRun(UUID runId) {
        BackupRun run = require(runId);
        // Die Schritte werden lazy geladen; hier innerhalb der Transaktion anstossen.
        run.getSteps().size();
        return run;
    }

    @Transactional(readOnly = true)
    public String readLog(UUID runId) {
        BackupRun run = require(runId);
        return run.getLogPath() == null ? "" : RunLogWriter.read(Path.of(run.getLogPath()));
    }

    private BackupRun require(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("Lauf %s nicht gefunden".formatted(runId)));
    }

    /**
     * Haelt fest, was der Runner meldet.
     *
     * <p>Fortschrittsmeldungen kommen mehrfach je Sekunde und werden bewusst nicht
     * gespeichert -- sie gehen nur ins Protokoll und spaeter an die Oberflaeche. Jede davon
     * in die Datenbank zu schreiben, waere Schreiblast ohne Nutzen.
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
            return addStep(runId, kind, targetId, description, image, redactedCommand);
        }

        @Override
        public void stepFinished(UUID stepId, StepStatus status, Integer exitCode, String message) {
            logWriter.append("→ %s%s".formatted(status,
                    exitCode == null ? "" : " (Rueckgabewert %d)".formatted(exitCode)));
            RunService.this.stepFinished(stepId, status, exitCode, message);
        }

        @Override
        public void progress(UUID stepId, ResticMessage.Progress progress) {
            // Absichtlich ohne Datenbankzugriff.
        }

        @Override
        public void logLine(String line) {
            logWriter.append(line);
        }
    }

    @Transactional
    UUID addStep(UUID runId, StepKind kind, UUID targetId, String description, String image,
            List<String> redactedCommand) {
        BackupRun run = require(runId);
        RunStep step = run.addStep(kind, targetId, description);
        step.markRunning(image, redactedCommand, null);
        runs.save(run);
        return step.getId();
    }

    @Transactional
    void stepFinished(UUID stepId, StepStatus status, Integer exitCode, String message) {
        steps.findById(stepId).ifPresent(step -> {
            step.finish(status, exitCode, message);
            steps.save(step);
        });
    }
}
