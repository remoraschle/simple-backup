package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.LogSink;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticRepository;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fuehrt ein restic-Kommando gegen ein Ziel aus.
 *
 * <p>Dasselbe Verfahren wie beim Sichern -- Repository-Adresse, Geheimnisse als Dateien,
 * Ausfuehrung im Runner. Lesen und Schreiben duerfen nicht auf verschiedenen Wegen zum
 * Repository kommen: Sonst findet die Wiederherstellung eines Tages nichts, und zwar genau
 * dann, wenn man sie braucht.
 */
@Component
class ResticJobs {

    private final BackupExecutor executor;
    private final ResticTargets targets;
    private final SecretRedactor redactor;

    ResticJobs(BackupExecutor executor, ResticTargets targets, SecretRedactor redactor) {
        this.executor = executor;
        this.targets = targets;
        this.redactor = redactor;
    }

    /**
     * Fuehrt das Kommando aus und sammelt die Ausgabe.
     *
     * @param extraMounts zusaetzliche Einhaengungen, etwa das Zielverzeichnis einer
     *                    Wiederherstellung
     * @param sink        bekommt jede Ausgabezeile sofort, fuer Live-Anzeigen
     */
    Result run(TargetConfig config, String targetName, List<String> command,
            List<VolumeMount> extraMounts, Duration timeout, LogSink sink) {

        ResticRepository repository = targets.repositoryFor(config, targetName);

        var builder = ExecutionRequest.builder(executor.defaultEnvironment(), command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(timeout)
                .env("RESTIC_REPOSITORY", repository.url());

        repository.environment().forEach(builder::env);
        repository.secretFiles().forEach(builder::secretFile);
        targets.mountsFor(config).forEach(builder::mount);
        extraMounts.forEach(builder::mount);

        List<String> output = new ArrayList<>();

        try {
            var running = executor.start(builder.build(), line -> {
                output.add(line);
                sink.accept(line);
            });

            ExecutionResult result = running.awaitCompletion(timeout);
            return new Result(result.isSuccess(), result.exitCode(), output, result.lastError());

        } catch (ExecutionException e) {
            return new Result(false, null, output, redactor.redact(String.valueOf(e.getMessage())));
        }
    }

    Result run(TargetConfig config, String targetName, List<String> command, Duration timeout) {
        return run(config, targetName, command, List.of(), timeout, LogSink.discarding());
    }

    /**
     * @param output alle Ausgabezeilen, bereits von Geheimnissen bereinigt
     * @param error  die letzte Fehlermeldung, wenn es eine gab
     */
    record Result(boolean successful, Integer exitCode, List<String> output, String error) {

        String joined() {
            return String.join("\n", output);
        }
    }
}
