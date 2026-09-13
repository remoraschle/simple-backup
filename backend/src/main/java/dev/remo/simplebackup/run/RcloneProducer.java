package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.secret.CredentialType;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Holt Daten von einer Gegenstelle, die rclone ansprechen kann.
 *
 * <p>Ein Werkzeug fuer S3 und SFTP statt zweier: rclone kennt beide Protokolle und bringt
 * Wiederholungen, Fortschritt und Nebenlaeufigkeit mit. Ein eigener Client je Protokoll waere
 * mehr Code fuer dieselbe Aufgabe -- und jeder davon mit eigenen Fehlern.
 *
 * <p>Die gesamte Konfiguration ist ein Geheimnis und geht als Datei in den Runner. Ueber die
 * Kommandozeile waeren die Schluessel in der Prozessliste sichtbar, ueber die Umgebung
 * dauerhaft ueber {@code docker inspect}.
 */
@Component
class RcloneProducer {

    private static final Logger log = LoggerFactory.getLogger(RcloneProducer.class);

    private static final String KEY_FILE = "sftp-key";

    private final BackupExecutor executor;
    private final CredentialService credentials;
    private final ResticTargets targets;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;
    private final RunProperties properties;

    RcloneProducer(BackupExecutor executor, CredentialService credentials, ResticTargets targets,
            SecretRedactor redactor, ObjectMapper objectMapper, RunProperties properties) {
        this.executor = executor;
        this.credentials = credentials;
        this.targets = targets;
        this.redactor = redactor;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    PreparedSource fetch(ExecutablePlan plan, String stagingDirectory, RunProgressListener listener) {
        VolumeMount staging = targets.translate(stagingDirectory, false);

        Job job = switch (plan.source()) {
            case SourceConfig.S3 s3 -> s3Job(s3);
            case SourceConfig.Sftp sftp -> sftpJob(sftp);
            default -> throw new IllegalStateException(
                    "rclone kann mit %s nichts anfangen".formatted(plan.source().type()));
        };

        run(plan, job, staging, listener);

        return new PreparedSource(List.of(staging.target()), List.of(staging), List.of(),
                stagingDirectory);
    }

    private Job s3Job(SourceConfig.S3 source) {
        var keys = objectMapper.readValue(credentials.reveal(source.credentialId()), S3Keys.class);

        return new Job("Abzug von " + source.bucket(),
                RcloneConfig.s3Path(source),
                Map.of(RcloneConfig.CONFIG_FILE,
                        RcloneConfig.forS3(source, keys.accessKeyId(), keys.secretAccessKey())));
    }

    /**
     * SFTP geht nur mit Schluessel.
     *
     * <p>Fuer ein Konto, das jede Nacht unbeaufsichtigt Daten holt, ist das ohnehin die
     * richtige Wahl -- und es erspart es, das Verschleierungsverfahren von rclone nachzubauen,
     * dessen Schluessel man nicht pruefen kann.
     */
    private Job sftpJob(SourceConfig.Sftp source) {
        var credential = credentials.find(source.credentialId());

        if (credential.type() != CredentialType.SSH_PRIVATE_KEY) {
            throw new IllegalStateException("""
                    Für SFTP wird ein privater SSH-Schlüssel gebraucht, kein Passwort. \
                    Hinterlegt ist ein Zugang der Art %s.""".formatted(credential.type()));
        }

        return new Job("Abzug von " + source.host() + ":" + source.path(),
                source.path(),
                Map.of(RcloneConfig.CONFIG_FILE, RcloneConfig.forSftpWithKey(source, KEY_FILE),
                        KEY_FILE, credentials.reveal(source.credentialId()),
                        // Der erwartete Hostschluessel. Ohne ihn nimmt rclone jeden -- und
                        // laedt die Daten im Zweifel bei jemand anderem ab.
                        RcloneConfig.KNOWN_HOSTS_FILE, source.hostKey().strip() + "\n"));
    }

    private void run(ExecutablePlan plan, Job job, VolumeMount staging, RunProgressListener listener) {
        List<String> command = RcloneConfig.copyCommand(job.remotePath(), staging.target());

        var builder = ExecutionRequest.builder(executor.defaultEnvironment(), command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(properties.acquireTimeout())
                .mount(staging)
                .label("simple-backup.plan-id", plan.planId().toString());

        job.secrets().forEach(builder::secretFile);
        ExecutionRequest request = builder.build();

        UUID stepId = listener.stepStarted(StepKind.ACQUIRE, null, job.description(),
                executor.defaultEnvironment(), redactor.redact(request.command()));

        try {
            ExecutionResult result = executor.start(request, listener::logLine)
                    .awaitCompletion(properties.acquireTimeout());

            listener.stepFinished(stepId, result.isSuccess() ? StepStatus.SUCCESS : StepStatus.FAILED,
                    result.exitCode(), result.isSuccess() ? job.description() : result.lastError());

            if (!result.isSuccess()) {
                throw new IllegalStateException("%s fehlgeschlagen: %s"
                        .formatted(job.description(), result.lastError()));
            }
            log.debug("{} abgeschlossen", job.description());

        } catch (ExecutionException e) {
            String message = redactor.redact(String.valueOf(e.getMessage()));
            listener.stepFinished(stepId, StepStatus.FAILED, null, message);
            throw new IllegalStateException("%s fehlgeschlagen: %s".formatted(job.description(), message), e);
        }
    }

    /** Was fuer einen Abzug gebraucht wird: Beschreibung, Pfad an der Gegenstelle, Geheimnisse. */
    private record Job(String description, String remotePath, Map<String, String> secrets) {
    }

    /** Form, in der ein Zugang vom Typ {@code S3_KEYPAIR} in der verschluesselten Ablage liegt. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record S3Keys(String accessKeyId, String secretAccessKey) {

        @Override
        public String toString() {
            return "S3Keys[accessKeyId=***]";
        }
    }
}
