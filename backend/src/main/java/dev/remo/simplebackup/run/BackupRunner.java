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
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.PathTranslationException;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.restic.ResticMessage;
import dev.remo.simplebackup.restic.ResticOutputParser;
import dev.remo.simplebackup.restic.ResticRepository;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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
    private final MountTranslator mountTranslator;
    private final CredentialService credentials;
    private final ResticOutputParser outputParser;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;

    BackupRunner(BackupExecutor executor, MountTranslator mountTranslator, CredentialService credentials,
            ResticOutputParser outputParser, SecretRedactor redactor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.mountTranslator = mountTranslator;
        this.credentials = credentials;
        this.outputParser = outputParser;
        this.redactor = redactor;
        this.objectMapper = objectMapper;
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

        for (ExecutableTarget target : plan.enabledTargets()) {
            try {
                outcomes.add(runTarget(plan, target, listener));
            } catch (RuntimeException e) {
                // Auch ein unerwarteter Fehler darf nur dieses eine Ziel betreffen.
                log.warn("Ziel {} des Plans {} fehlgeschlagen: {}", target.name(), plan.planName(),
                        e.getMessage());
                outcomes.add(TargetOutcome.failed(target, redactor.redact(String.valueOf(e.getMessage()))));
            }
        }
        return outcomes;
    }

    private TargetOutcome runTarget(ExecutablePlan plan, ExecutableTarget target,
            RunProgressListener listener) {

        if (target.mode() != TargetMode.RESTIC) {
            return TargetOutcome.skipped(target, "Der Spiegel-Modus ist noch nicht umgesetzt");
        }

        ResticRepository repository = buildRepository(target);
        List<VolumeMount> mounts = mountsFor(plan, target);

        // Schritt 1: Gibt es das Repository schon? Der Rueckgabewert sagt es, ohne dass eine
        // Ausgabe gedeutet werden muesste.
        StepOutcome probe = execute(plan, target, StepKind.PREPARE, "Repository prüfen",
                ResticCommands.catConfig(), repository, mounts, listener, Duration.ofMinutes(5));

        if (!probe.successful()) {
            StepOutcome init = execute(plan, target, StepKind.PREPARE, "Repository anlegen",
                    ResticCommands.init(), repository, mounts, listener, Duration.ofMinutes(10));

            if (!init.successful()) {
                return TargetOutcome.failed(target,
                        "Repository liess sich nicht anlegen: " + init.message());
            }
        }

        // Schritt 2: die eigentliche Sicherung.
        List<String> paths = sourcePathsInRunner(plan);
        var backup = ResticCommands.backup(paths, plan.resticHost(), plan.resticTag(),
                excludesOf(plan), null, oneFileSystemOf(plan));

        StepOutcome transfer = execute(plan, target, StepKind.TRANSFER,
                "Sicherung auf " + target.name(), backup, repository, mounts, listener, plan.timeout());

        return transfer.successful()
                ? TargetOutcome.succeeded(target, transfer.summary())
                : TargetOutcome.failed(target, transfer.message());
    }

    private StepOutcome execute(ExecutablePlan plan, ExecutableTarget target, StepKind kind,
            String description, List<String> command, ResticRepository repository,
            List<VolumeMount> mounts, RunProgressListener listener, Duration timeout) {

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
            listener.stepFinished(stepHandle, toStepStatus(result.status()), result.exitCode(),
                    result.isSuccess() ? description : result.lastError());

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

    /**
     * Baut die Repository-Adresse und loest die Zugangsdaten auf.
     *
     * <p>Klartext entsteht erst hier, unmittelbar vor dem Start des Runners, und wird
     * gleichzeitig beim Redaktor angemeldet.
     */
    private ResticRepository buildRepository(ExecutableTarget target) {
        TargetConfig config = target.config();
        UUID passwordId = config.repositoryPasswordCredentialId();

        if (passwordId == null) {
            throw new IllegalStateException(
                    "Dem Ziel '%s' fehlt das Repository-Passwort".formatted(target.name()));
        }
        String password = credentials.reveal(passwordId);

        return switch (config) {
            case TargetConfig.LocalPath localPath -> ResticRepository.localPath(
                    pathInRunner(localPath.path()), password);

            case TargetConfig.S3 s3 -> {
                var keys = objectMapper.readValue(credentials.reveal(s3.credentialId()),
                        S3Credentials.class);
                yield ResticRepository.s3(s3.endpoint(), s3.bucket(), s3.prefix(),
                        keys.accessKeyId(), keys.secretAccessKey(), password);
            }
        };
    }

    /**
     * Die Einhaengungen, die der Runner braucht.
     *
     * <p>Quellen immer schreibgeschuetzt: Das Werkzeug hat auf Originaldaten nichts zu
     * schreiben, und ein schreibgeschuetzter Mount macht einen Fehler unmoeglich statt nur
     * unwahrscheinlich.
     */
    private List<VolumeMount> mountsFor(ExecutablePlan plan, ExecutableTarget target) {
        List<VolumeMount> mounts = new ArrayList<>();

        if (plan.source() instanceof SourceConfig.LocalPath localPath) {
            localPath.paths().forEach(path -> mounts.add(translate(path, true)));
        }
        if (target.config() instanceof TargetConfig.LocalPath localTarget) {
            mounts.add(translate(localTarget.path(), false));
        }
        return mounts;
    }

    private VolumeMount translate(String containerPath, boolean readOnly) {
        try {
            return mountTranslator.translate(containerPath, readOnly);
        } catch (PathTranslationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Im Runner erscheinen die Pfade unter derselben Adresse wie im Backend. */
    private String pathInRunner(String containerPath) {
        return translate(containerPath, false).target();
    }

    private List<String> sourcePathsInRunner(ExecutablePlan plan) {
        if (plan.source() instanceof SourceConfig.LocalPath localPath) {
            return localPath.paths().stream().map(path -> translate(path, true).target()).toList();
        }
        throw new IllegalStateException(
                "Quellen vom Typ %s sind noch nicht umgesetzt".formatted(plan.source().type()));
    }

    private static List<String> excludesOf(ExecutablePlan plan) {
        return plan.source() instanceof SourceConfig.LocalPath localPath
                ? localPath.excludes() : List.of();
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
