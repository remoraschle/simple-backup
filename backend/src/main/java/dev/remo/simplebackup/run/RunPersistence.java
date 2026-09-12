package dev.remo.simplebackup.run;

import dev.remo.simplebackup.shared.NotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt den Verlauf eines Laufs in die Datenbank.
 *
 * <p><b>Eine eigene Bean und keine Methoden im Dienst selbst.</b> Spring legt
 * {@code @Transactional} als Stellvertreter um die Bean; ein Aufruf innerhalb derselben
 * Klasse geht daran vorbei und liefe ohne Transaktion. Genau das ist hier der Fall, weil der
 * Fortschritt aus einer inneren Klasse gemeldet wird -- und ohne Transaktion scheitert schon
 * das Nachladen der Schritte.
 */
@Component
class RunPersistence {

    private final RunRepository runs;
    private final RunStepRepository steps;

    RunPersistence(RunRepository runs, RunStepRepository steps) {
        this.runs = runs;
        this.steps = steps;
    }

    @Transactional
    BackupRun create(UUID planId, RunTrigger trigger) {
        return runs.save(new BackupRun(planId, trigger, 1));
    }

    @Transactional
    void markRunning(UUID runId, String logPath) {
        BackupRun run = require(runId);
        run.markRunning();
        run.setLogPath(logPath);
        runs.save(run);
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
    void finishStep(UUID stepId, StepStatus status, Integer exitCode, String message) {
        steps.findById(stepId).ifPresent(step -> {
            step.finish(status, exitCode, message);
            steps.save(step);
        });
    }

    /**
     * Schliesst den Lauf ab und leitet seinen Gesamtzustand aus den Schritten ab.
     *
     * @return der abgeleitete Zustand samt Fehlerkurzfassung
     */
    @Transactional
    Outcome complete(UUID runId, List<BackupRunner.TargetOutcome> outcomes) {
        BackupRun run = require(runId);

        // Kennzahlen aus der ersten Abschlussmeldung: Die Quelle wird einmal gelesen, die
        // Zahlen sind fuer alle Ziele dieselben.
        outcomes.stream()
                .map(BackupRunner.TargetOutcome::summary)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(summary -> run.recordMetrics(summary.totalBytesProcessed(), summary.dataAdded(),
                        summary.filesNew(), summary.filesChanged(), summary.filesUnmodified()));

        RunStatus status = run.deriveStatus();
        run.finish(status, summariseFailures(outcomes));
        runs.save(run);

        return new Outcome(status, run.getErrorSummary(), run.getFinishedAt());
    }

    @Transactional
    void finish(UUID runId, RunStatus status, String message) {
        runs.findById(runId).ifPresent(run -> {
            run.finish(status, message);
            runs.save(run);
        });
    }

    @Transactional(readOnly = true)
    BackupRun findWithSteps(UUID runId) {
        BackupRun run = require(runId);
        // Innerhalb der Transaktion nachladen, damit der Aufrufer sie ohne Sitzung lesen kann.
        run.getSteps().size();
        return run;
    }

    @Transactional(readOnly = true)
    boolean hasActiveRun(UUID planId) {
        return runs.existsByPlanIdAndStatusIn(planId, List.of(RunStatus.QUEUED, RunStatus.RUNNING));
    }

    /** Fasst zusammen, was schiefging -- kurz genug fuer eine Uebersicht. */
    private static String summariseFailures(List<BackupRunner.TargetOutcome> outcomes) {
        List<String> failures = outcomes.stream()
                .filter(outcome -> !outcome.successful())
                .map(outcome -> "%s: %s".formatted(outcome.targetName(), outcome.message()))
                .toList();

        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    private BackupRun require(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("Lauf %s nicht gefunden".formatted(runId)));
    }

    record Outcome(RunStatus status, String errorSummary, java.time.Instant finishedAt) {
    }
}
