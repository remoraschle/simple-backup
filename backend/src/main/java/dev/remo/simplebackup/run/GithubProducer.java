package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.SourceType;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spiegelt Repositories von GitHub.
 *
 * <p><b>Warum ein Mirror und kein Checkout:</b> Ein Mirror enthaelt alle Branches, Tags und
 * Notes. Ein Checkout enthaelt einen Stand eines Zweiges -- und wer das erst im Ernstfall
 * bemerkt, hat den Rest seiner Geschichte verloren.
 *
 * <p><b>Warum zusaetzlich Metadaten:</b> Issues, Pull Requests und Releases liegen nicht im
 * Git-Repository. Der Code liesse sich aus jedem Klon wiederherstellen, die Diskussion
 * darueber nicht.
 *
 * <p>Der Token geht ueber eine Anmeldedatei, nie in die Adresse: Ein Token in der URL steht
 * in der Prozessliste, in der Fehlermeldung und am Ende in {@code .git/config}.
 */
@Component
class GithubProducer implements SourceProducer {

    private static final Logger log = LoggerFactory.getLogger(GithubProducer.class);

    /** Dateiname der Anmeldedaten im Runner, im Format von {@code git-credential-store}. */
    private static final String CREDENTIALS_FILE = "git-credentials";

    private final BackupExecutor executor;
    private final CredentialService credentials;
    private final GitHubApi api;
    private final ResticTargets targets;
    private final SecretRedactor redactor;
    private final RunProperties properties;

    GithubProducer(BackupExecutor executor, CredentialService credentials, GitHubApi api,
            ResticTargets targets, SecretRedactor redactor, RunProperties properties) {
        this.executor = executor;
        this.credentials = credentials;
        this.api = api;
        this.targets = targets;
        this.redactor = redactor;
        this.properties = properties;
    }

    @Override
    public SourceType type() {
        return SourceType.GITHUB;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {

        SourceConfig.GitHub source = (SourceConfig.GitHub) plan.source();
        VolumeMount staging = targets.translate(stagingDirectory, false);

        String token = credentials.reveal(source.credentialId());
        List<GitHubApi.Repository> repositories = discover(source, token, listener);

        for (GitHubApi.Repository repository : repositories) {
            mirror(plan, repository, staging, token, listener);

            if (source.includeMetadata()) {
                writeMetadata(repository, token, stagingDirectory, listener);
            }
        }

        return new PreparedSource(List.of(staging.target()), List.of(staging), List.of(),
                stagingDirectory);
    }

    /**
     * Welche Repositories gesichert werden.
     *
     * <p>Ohne Angabe alle des Eigentuemers: Ein neues Repository waere sonst so lange
     * ungesichert, bis jemand daran denkt -- und daran denkt niemand.
     */
    private List<GitHubApi.Repository> discover(SourceConfig.GitHub source, String token,
            RunProgressListener listener) {

        List<GitHubApi.Repository> all = api.listRepositories(source.owner(), token,
                source.includeForks());

        List<GitHubApi.Repository> selected = source.repositories().isEmpty() ? all
                : all.stream().filter(repository -> source.repositories().contains(repository.name())).toList();

        listener.logLine("%d Repositories von %s werden gesichert".formatted(selected.size(),
                source.owner()));

        if (selected.isEmpty()) {
            throw new IllegalStateException(
                    "Zu %s gibt es keine Repositories, die auf die Auswahl passen".formatted(source.owner()));
        }
        return selected;
    }

    private void mirror(ExecutablePlan plan, GitHubApi.Repository repository, VolumeMount staging,
            String token, RunProgressListener listener) {

        String directory = staging.target() + "/" + repository.name() + ".git";

        // credential.helper bekommt nur den Pfad zur Datei -- der Token selbst bleibt darin.
        List<String> command = List.of("git",
                "-c", "credential.helper=store --file=" + ExecutionRequest.SECRETS_DIRECTORY
                        + "/" + CREDENTIALS_FILE,
                "clone", "--mirror", repository.cloneUrl(), directory);

        var request = ExecutionRequest.builder(properties.gitImage(), command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(properties.acquireTimeout())
                .mount(staging)
                .secretFile(CREDENTIALS_FILE, credentialsFile(token))
                .label("simple-backup.plan-id", plan.planId().toString())
                .build();

        UUID stepId = listener.stepStarted(StepKind.ACQUIRE, null, "Spiegeln von " + repository.fullName(),
                properties.gitImage(), redactor.redact(request.command()));

        try {
            ExecutionResult result = executor.start(request, listener::logLine)
                    .awaitCompletion(properties.acquireTimeout());

            listener.stepFinished(stepId, result.isSuccess() ? StepStatus.SUCCESS : StepStatus.FAILED,
                    result.exitCode(), result.isSuccess() ? repository.fullName() : result.lastError());

            if (!result.isSuccess()) {
                throw new IllegalStateException("Spiegeln von %s fehlgeschlagen: %s"
                        .formatted(repository.fullName(), result.lastError()));
            }
            log.debug("{} gespiegelt", repository.fullName());

        } catch (ExecutionException e) {
            String message = redactor.redact(String.valueOf(e.getMessage()));
            listener.stepFinished(stepId, StepStatus.FAILED, null, message);
            throw new IllegalStateException("Spiegeln von %s fehlgeschlagen: %s"
                    .formatted(repository.fullName(), message), e);
        }
    }

    /**
     * Metadaten neben den Klon legen.
     *
     * <p>Scheitert das, ist es kein Grund, die Sicherung abzubrechen: Der Code ist der
     * wertvollere Teil, und ihn wegen einer Abfrage fallen zu lassen waere die falsche
     * Abwaegung.
     */
    private void writeMetadata(GitHubApi.Repository repository, String token, String stagingDirectory,
            RunProgressListener listener) {

        try {
            Path file = Path.of(stagingDirectory, repository.name() + ".metadata.json");
            Files.writeString(file, api.metadata(repository.fullName(), token), StandardCharsets.UTF_8);

        } catch (IOException | RuntimeException e) {
            log.warn("Metadaten von {} liessen sich nicht sichern", repository.fullName(), e);
            listener.logLine("Metadaten von %s konnten nicht geholt werden: %s"
                    .formatted(repository.fullName(), e.getMessage()));
        }
    }

    /** Das Format von {@code git-credential-store}: eine vollstaendige Adresse je Zeile. */
    private static String credentialsFile(String token) {
        return "https://x-access-token:%s@github.com%n".formatted(token);
    }
}
