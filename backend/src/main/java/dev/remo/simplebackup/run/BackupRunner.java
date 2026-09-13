package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.ExecutableTarget;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.catalog.TargetMode;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.ExecutionStatus;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.restic.ResticMessage;
import dev.remo.simplebackup.restic.ResticOutputParser;
import dev.remo.simplebackup.restic.ResticRepository;
import dev.remo.simplebackup.snapshot.ResticTargets;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fuehrt einen Plan aus: Schritte bauen, ausfuehren, auswerten.
 *
 * <p>Arbeitet ausschliesslich auf Wertobjekten und ohne offene Transaktion. Ein Backup laeuft
 * Stunden; solange eine Datenbanktransaktion offen zu halten waere die schlechtere Wahl.
 *
 * <p>Der Fortschritt wird ueber {@link RunProgressListener} gemeldet, damit der Aufrufer ihn
 * speichern und weiterleiten kann, ohne dass diese Klasse die Datenbank kennt.
 */
@Component
public class BackupRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupExecutor executor;
    private final ResticTargets targets;
    private final ResticOutputParser outputParser;
    private final SecretRedactor redactor;
    private final SourceProducers producers;
    private final RunProperties properties;

    /**
     * Eigenes Zeitlimit fuers Aufraeumen: {@code prune} liest das halbe Repository und
     * braucht deutlich laenger als die Sicherung selbst, die nur Aenderungen schreibt.
     */
    private final Duration pruneTimeout;

    BackupRunner(BackupExecutor executor, ResticTargets targets, ResticOutputParser outputParser,
            SecretRedactor redactor, SourceProducers producers, RunProperties properties) {
        this.executor = executor;
        this.targets = targets;
        this.outputParser = outputParser;
        this.redactor = redactor;
        this.producers = producers;
        this.properties = properties;
        this.pruneTimeout = properties.pruneTimeout();
    }

    /**
     * Fuehrt alle Schritte des Plans aus.
     *
     * <p>Ein gescheitertes Ziel beendet den Lauf nicht: Die uebrigen Ziele werden trotzdem
     * bedient. Genau daraus entsteht der Zustand "Teilerfolg" -- NAS erreichbar, S3 nicht.
     *
     * @return das Ergebnis je Ziel, in der Reihenfolge der Ziele
     */
    public List<TargetOutcome> run(ExecutablePlan plan, RunProgressListener listener) {
        List<TargetOutcome> outcomes = new ArrayList<>();

        // Erst beschaffen, dann sichern. Fuer ein Verzeichnis ist der erste Schritt nichts
        // weiter als der Pfad; fuer eine Datenbank ein Dump, fuer GitHub ein Klon. Scheitert
        // die Beschaffung, gibt es nichts zu uebertragen -- dann endet der Lauf hier.
        String staging = prepareStagingDirectory(plan);
        PreparedSource prepared = producers.prepare(plan, staging, listener);

        try {
            for (ExecutableTarget target : plan.enabledTargets()) {
                try {
                    outcomes.add(runTarget(plan, target, prepared, listener));
                } catch (RuntimeException e) {
                    // Auch ein unerwarteter Fehler darf nur dieses eine Ziel betreffen.
                    log.warn("Ziel {} des Plans {} fehlgeschlagen: {}", target.name(), plan.planName(),
                            e.getMessage());
                    outcomes.add(TargetOutcome.failed(target,
                            redactor.redact(String.valueOf(e.getMessage()))));
                }
            }
            return outcomes;

        } finally {
            discard(prepared, listener);
        }
    }

    /**
     * Raeumt Zwischenstaende weg.
     *
     * <p>Auch nach einem Fehlschlag: Ein liegengebliebener Datenbank-Dump ist unverschluesselter
     * Klartext auf der Platte -- genau das, was diese Anwendung sonst vermeidet.
     */
    private void discard(PreparedSource prepared, RunProgressListener listener) {
        if (prepared.stagingDirectory() == null) {
            return;
        }
        UUID stepId = listener.stepStarted(StepKind.CLEANUP, null, "Zwischenstand aufräumen",
                executor.defaultEnvironment(), List.of());
        try {
            deleteRecursively(Path.of(prepared.stagingDirectory()));
            listener.stepFinished(stepId, StepStatus.SUCCESS, 0, "Zwischenstand aufgeräumt");

        } catch (RuntimeException e) {
            log.error("Zwischenstand {} liess sich nicht aufraeumen", prepared.stagingDirectory(), e);
            listener.stepFinished(stepId, StepStatus.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Legt das Verzeichnis fuer Zwischenstaende an -- leer.
     *
     * <p>Vom Backend und nicht vom Runner: Der bekommt es als Einhaengung und kann es nicht
     * selbst erzeugen. Reste eines abgebrochenen Laufs fliegen vorher raus, sonst landete
     * ein halber Dump von gestern in der heutigen Sicherung.
     */
    private String prepareStagingDirectory(ExecutablePlan plan) {
        Path staging = Path.of(properties.stagingDirectory(), "plan-" + plan.planId());
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);
            return staging.toString();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Arbeitsverzeichnis %s liess sich nicht anlegen: %s".formatted(staging, e.getMessage()), e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new IllegalStateException("Konnte " + entry + " nicht loeschen", e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Aufraeumen fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private TargetOutcome runTarget(ExecutablePlan plan, ExecutableTarget target,
            PreparedSource prepared, RunProgressListener listener) {

        if (target.mode() == TargetMode.MIRROR) {
            return runMirror(plan, target, prepared, listener);
        }

        ResticRepository repository = targets.repositoryFor(target.config(), target.name());

        List<VolumeMount> mounts = new ArrayList<>(prepared.mounts());
        mounts.addAll(targets.mountsFor(target.config()));

        // Schritt 1: Gibt es das Repository schon? Der Rueckgabewert sagt es, ohne dass eine
        // Ausgabe gedeutet werden muesste.
        //
        // Ein Fehlschlag ist hier der Normalfall beim ersten Lauf und kein Problem. Er wird
        // deshalb als uebersprungen vermerkt -- "fehlgeschlagen" in der Historie wuerde bei
        // jedem neuen Ziel nach einem Fehler aussehen, den es nie gab.
        StepOutcome probe = execute(plan, target, StepKind.PREPARE, "Repository prüfen",
                ResticCommands.catConfig(), repository, mounts, listener, Duration.ofMinutes(5),
                "Noch nicht vorhanden — wird angelegt");

        if (!probe.successful()) {
            StepOutcome init = execute(plan, target, StepKind.PREPARE, "Repository anlegen",
                    ResticCommands.init(), repository, mounts, listener, Duration.ofMinutes(10), null);

            if (!init.successful()) {
                return TargetOutcome.failed(target,
                        "Repository liess sich nicht anlegen: " + init.message());
            }
        }

        // Schritt 2: die eigentliche Sicherung.
        var backup = ResticCommands.backup(prepared.paths(), plan.resticHost(), plan.resticTag(),
                prepared.excludes(), null, oneFileSystemOf(plan));

        StepOutcome transfer = execute(plan, target, StepKind.TRANSFER,
                "Sicherung auf " + target.name(), backup, repository, mounts, listener,
                plan.timeout(), null);

        if (!transfer.successful()) {
            return TargetOutcome.failed(target, transfer.message());
        }

        // Schritt 3: aufraeumen. Erst nach erfolgreicher Sicherung -- sonst loeschte eine
        // Aufbewahrungsregel alte Snapshots, ohne dass ein neuer dazugekommen waere.
        applyRetention(plan, target, repository, mounts, listener);

        return TargetOutcome.succeeded(target, transfer.summary());
    }

    /**
     * Spiegelt die Quelle als direkt lesbare Kopie.
     *
     * <p>Kein restic, keine Verschluesselung, keine Snapshots -- genau das ist der Zweck:
     * Eine Kopie, die man ohne dieses Werkzeug und ohne Passwort oeffnen kann. Der Preis ist
     * ebenso klar: Es gibt nur den letzten Stand, und eine geloeschte Datei ist nach dem
     * naechsten Lauf auch in der Kopie geloescht.
     */
    private TargetOutcome runMirror(ExecutablePlan plan, ExecutableTarget target,
            PreparedSource prepared, RunProgressListener listener) {

        if (!(target.config() instanceof TargetConfig.LocalPath localTarget)) {
            return TargetOutcome.failed(target,
                    "Der Spiegel-Modus schreibt in ein Verzeichnis, nicht nach " + target.config().type());
        }

        VolumeMount destination = targets.translate(localTarget.path(), false);

        List<VolumeMount> mounts = new ArrayList<>(prepared.mounts());
        mounts.add(destination);

        List<String> command = rsyncCommand(prepared, destination.target());

        var builder = ExecutionRequest.builder(executor.defaultEnvironment(), command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(plan.timeout())
                .label("simple-backup.plan-id", plan.planId().toString());

        mounts.forEach(builder::mount);
        ExecutionRequest request = builder.build();

        UUID stepId = listener.stepStarted(StepKind.TRANSFER, target.targetId(),
                "Spiegeln auf " + target.name(), executor.defaultEnvironment(),
                redactor.redact(request.command()));

        try {
            ExecutionResult result = executor.start(request, listener::logLine)
                    .awaitCompletion(plan.timeout());

            listener.stepFinished(stepId, toStepStatus(result.status()), result.exitCode(),
                    result.isSuccess() ? "Spiegeln auf " + target.name() : result.lastError());

            return result.isSuccess()
                    ? TargetOutcome.succeeded(target, null)
                    : TargetOutcome.failed(target, result.lastError());

        } catch (ExecutionException e) {
            String message = redactor.redact(String.valueOf(e.getMessage()));
            listener.stepFinished(stepId, StepStatus.FAILED, null, message);
            return TargetOutcome.failed(target, message);
        }
    }

    /**
     * Der rsync-Aufruf.
     *
     * <p>{@code --delete} ist Absicht und der Kern eines Spiegels: Was an der Quelle weg ist,
     * verschwindet auch in der Kopie. Wer das nicht will, will keinen Spiegel, sondern
     * Snapshots -- und dafuer gibt es den restic-Modus.
     */
    private static List<String> rsyncCommand(PreparedSource prepared, String destination) {
        var command = new ArrayList<>(List.of("rsync",
                "--archive",
                "--delete",
                "--numeric-ids",
                "--human-readable",
                "--info=stats2"));

        for (String pattern : prepared.excludes()) {
            command.add("--exclude");
            command.add(pattern);
        }
        command.addAll(prepared.paths());
        command.add(destination.endsWith("/") ? destination : destination + "/");

        return List.copyOf(command);
    }

    /**
     * Wendet die Aufbewahrungsregel an.
     *
     * <p>Ein gescheitertes Aufraeumen macht die Sicherung nicht ungueltig: Die Daten sind
     * geschrieben. Der Schritt steht trotzdem als fehlgeschlagen in der Historie, denn ein
     * Repository, das nicht mehr aufgeraeumt wird, laeuft irgendwann voll -- und dann
     * scheitert auch das Sichern.
     */
    private void applyRetention(ExecutablePlan plan, ExecutableTarget target,
            ResticRepository repository, List<VolumeMount> mounts, RunProgressListener listener) {

        if (plan.retention() == null) {
            return;
        }
        var forget = ResticCommands.forget(plan.retention(), plan.resticHost(), plan.resticTag(),
                true, false);

        StepOutcome prune = execute(plan, target, StepKind.PRUNE,
                "Alte Sicherungen aufräumen auf " + target.name(), forget, repository, mounts,
                listener, pruneTimeout, null);

        if (!prune.successful()) {
            log.warn("Aufraeumen auf {} fehlgeschlagen: {}", target.name(), prune.message());
        }
    }

    /**
     * Fuehrt einen Schritt aus.
     *
     * @param expectedFailureNote ist gesetzt, wenn ein Fehlschlag zum erwarteten Ablauf
     *                            gehoert. Der Schritt gilt dann als uebersprungen und traegt
     *                            diesen Text statt der Fehlerausgabe des Werkzeugs.
     */
    private StepOutcome execute(ExecutablePlan plan, ExecutableTarget target, StepKind kind,
            String description, List<String> command, ResticRepository repository,
            List<VolumeMount> mounts, RunProgressListener listener, Duration timeout,
            String expectedFailureNote) {

        var builder = ExecutionRequest.builder(executor.defaultEnvironment(), command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(timeout)
                .env("RESTIC_REPOSITORY", repository.url())
                .label("simple-backup.plan-id", plan.planId().toString());

        repository.environment().forEach(builder::env);
        repository.secretFiles().forEach(builder::secretFile);
        mounts.forEach(builder::mount);

        ExecutionRequest request = builder.build();

        // Die gespeicherte Kommandozeile ist bereits bereinigt; Geheimnisse stehen ohnehin
        // in Dateien, aber ein Pfad kann einen Zugang enthalten.
        var stepHandle = listener.stepStarted(kind, target.targetId(), description,
                executor.defaultEnvironment(),
                redactor.redact(request.command()));

        var lastSummary = new AtomicReference<ResticMessage.Summary>();

        try {
            var running = executor.start(request, line -> {
                listener.logLine(line);
                outputParser.parse(line).ifPresent(message -> {
                    switch (message) {
                        case ResticMessage.Progress progress -> listener.progress(stepHandle, progress);
                        case ResticMessage.Summary summary -> lastSummary.set(summary);
                        case ResticMessage.Failure failure -> listener.logLine(
                                "Fehler: %s (%s)".formatted(failure.message(), failure.item()));
                    }
                });
            });

            ExecutionResult result = running.awaitCompletion(timeout);

            boolean expectedFailure = !result.isSuccess() && expectedFailureNote != null;
            StepStatus status = expectedFailure ? StepStatus.SKIPPED : toStepStatus(result.status());
            String message = result.isSuccess() ? description
                    : expectedFailure ? expectedFailureNote : result.lastError();

            listener.stepFinished(stepHandle, status, result.exitCode(), message);

            return new StepOutcome(result.isSuccess(), result.lastError(), lastSummary.get());

        } catch (ExecutionException e) {
            String message = redactor.redact(e.getMessage());
            listener.stepFinished(stepHandle, StepStatus.FAILED, null, message);
            return new StepOutcome(false, message, null);
        }
    }

    private static StepStatus toStepStatus(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> StepStatus.SUCCESS;
            case FAILED -> StepStatus.FAILED;
            case TIMEOUT -> StepStatus.TIMEOUT;
            case CANCELLED -> StepStatus.CANCELLED;
        };
    }

    private static boolean oneFileSystemOf(ExecutablePlan plan) {
        return plan.source() instanceof SourceConfig.LocalPath localPath && localPath.oneFileSystem();
    }

    /** Ergebnis eines einzelnen Schritts. */
    private record StepOutcome(boolean successful, String message, ResticMessage.Summary summary) {
    }

    /**
     * Ergebnis fuer ein Ziel.
     *
     * @param summary Kennzahlen des Laufs, sofern restic sie geliefert hat
     */
    public record TargetOutcome(
            UUID targetId,
            String targetName,
            boolean successful,
            boolean skipped,
            String message,
            ResticMessage.Summary summary) {

        static TargetOutcome succeeded(ExecutableTarget target, ResticMessage.Summary summary) {
            return new TargetOutcome(target.targetId(), target.name(), true, false, null, summary);
        }

        static TargetOutcome failed(ExecutableTarget target, String message) {
            return new TargetOutcome(target.targetId(), target.name(), false, false, message, null);
        }

        static TargetOutcome skipped(ExecutableTarget target, String reason) {
            return new TargetOutcome(target.targetId(), target.name(), false, true, reason, null);
        }
    }
}
