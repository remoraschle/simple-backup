package dev.remo.simplebackup.engine.docker;

import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.ExecutionStatus;
import dev.remo.simplebackup.engine.LogSink;
import dev.remo.simplebackup.engine.RunningExecution;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fuehrt jeden Schritt in einem eigenen, kurzlebigen Container aus.
 *
 * <p>Der Ablauf ist bewusst in dieser Reihenfolge: anlegen, Geheimnisse hineinkopieren,
 * starten. Die Geheimnisse muessen vor dem Start im Container liegen, sonst liefe das
 * Kommando bereits, waehrend sie noch fehlen.
 *
 * <p><b>{@code AutoRemove} bleibt aus.</b> Ein automatisch entfernter Container nimmt
 * Rueckgabewert und Logausgabe mit ins Grab. Entfernt wird erst, nachdem das Ergebnis
 * ausgewertet ist.
 */
public class DockerJobExecutor implements BackupExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerJobExecutor.class);

    /** Frist zwischen SIGTERM und SIGKILL. */
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(10);

    private final DockerApiClient client;
    private final DockerProperties properties;
    private final SecretRedactor redactor;

    public DockerJobExecutor(DockerApiClient client, DockerProperties properties, SecretRedactor redactor) {
        this.client = client;
        this.properties = properties;
        this.redactor = redactor;
    }

    @Override
    public RunningExecution start(ExecutionRequest request, LogSink logSink) {
        Instant startedAt = Instant.now();
        requireImage(request.image());

        String containerId = null;
        try {
            containerId = client.createContainer(toCreateRequest(request), containerNameFor(request));

            if (!request.secretFiles().isEmpty()) {
                // Vor dem Start: Danach liefe das Kommando bereits, waehrend die Dateien
                // noch fehlten.
                client.copyFilesInto(containerId, ExecutionRequest.SECRETS_DIRECTORY,
                        request.secretFiles(), 0600);
                request.secretFiles().values().forEach(redactor::register);
            }

            client.startContainer(containerId);
            return new ContainerExecution(request, containerId, startedAt, logSink);

        } catch (DockerApiException e) {
            // Ein halb angelegter Container darf nicht zurueckbleiben.
            if (containerId != null) {
                safeRemove(containerId);
            }
            throw new ExecutionException(
                    "Schritt %s liess sich nicht starten: %s".formatted(request.executionId(), e.getMessage()), e);
        }
    }

    @Override
    public Optional<RunningExecution> reattach(String executionId, LogSink logSink) {
        var containers = client.listByLabel(DockerLabels.EXECUTION_ID, executionId);

        if (containers.isEmpty()) {
            return Optional.empty();
        }
        if (containers.size() > 1) {
            log.warn("Mehrere Container tragen die Kennung {}; der erste wird verwendet", executionId);
        }

        String containerId = containers.getFirst().id();
        log.info("Hänge mich wieder an Container {} für Schritt {}", shortId(containerId), executionId);

        // Die Anfrage steht nach einem Neustart nicht mehr zur Verfuegung. Fuer das Warten
        // auf das Ende genuegt die Kennung; Einhaengungen und Kommando sind bereits gesetzt.
        return Optional.of(new ContainerExecution(executionId, containerId, Instant.now(), logSink));
    }

    /**
     * Stellt sicher, dass das Image vorhanden ist.
     *
     * <p>Die Pruefung passiert vorab und nicht mitten im Lauf: Ein Heimserver ohne
     * Internetzugang soll trotzdem sichern koennen und im Zweifel eine klare Meldung
     * bekommen statt eines Abbruchs nach zwei Stunden.
     */
    private void requireImage(String image) {
        if (client.imageExists(image)) {
            return;
        }
        log.info("Image {} fehlt und wird geladen", image);
        try {
            client.pullImage(image, Duration.ofMinutes(15));
        } catch (DockerApiException e) {
            throw new ExecutionException("""
                    Das Runner-Image %s ist nicht vorhanden und liess sich nicht laden: %s

                    Ohne Internetzugang muss es vorab bereitstehen: docker pull %s"""
                    .formatted(image, e.getMessage(), image), e);
        }
    }

    private DockerDto.CreateContainer toCreateRequest(ExecutionRequest request) {
        Map<String, String> labels = new LinkedHashMap<>(request.labels());
        labels.put(DockerLabels.MANAGED_BY, DockerLabels.MANAGED_BY_VALUE);
        labels.put(DockerLabels.EXECUTION_ID, request.executionId());

        List<String> environment = request.environment().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();

        List<String> binds = request.mounts().stream().map(VolumeMount::toBindSpec).toList();

        var limits = request.limits();
        var hostConfig = new DockerDto.HostConfig(
                binds,
                limits.memoryBytes(),
                limits.cpuQuota() == null ? null : (long) (limits.cpuQuota() * 1_000_000_000L),
                limits.networkMode(),
                // Siehe Klassenkommentar: sonst gingen Rueckgabewert und Log verloren.
                false,
                false,
                null,
                // Verhindert, dass ein Prozess im Container ueber setuid Rechte hinzugewinnt.
                List.of("no-new-privileges"));

        return new DockerDto.CreateContainer(
                request.image(),
                request.command(),
                environment,
                labels,
                properties.runnerUser(),
                // Ohne TTY, damit der Logstrom gerahmt kommt und Werkzeuge sich verhalten
                // wie in einem Skript.
                false,
                true,
                true,
                hostConfig);
    }

    /** Erkennbarer Name in {@code docker ps}, ohne Kollisionen bei Wiederholungen. */
    private static String containerNameFor(ExecutionRequest request) {
        String suffix = request.executionId().replaceAll("[^a-zA-Z0-9_.-]", "");
        return "simple-backup-" + suffix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private void safeRemove(String containerId) {
        try {
            client.remove(containerId, true);
        } catch (DockerApiException e) {
            log.warn("Container {} liess sich nicht entfernen: {}", shortId(containerId), e.getMessage());
        }
    }

    private static String shortId(String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    private final class ContainerExecution implements RunningExecution {

        private final String executionId;
        private final String containerId;
        private final Instant startedAt;
        private final List<String> secretsToForget;
        private final AtomicReference<String> lastLine = new AtomicReference<>();
        private final Thread logReader;
        private volatile boolean cancelled;

        private ContainerExecution(ExecutionRequest request, String containerId, Instant startedAt,
                LogSink logSink) {
            this(request.executionId(), containerId, startedAt, logSink,
                    List.copyOf(request.secretFiles().values()));
        }

        private ContainerExecution(String executionId, String containerId, Instant startedAt, LogSink logSink) {
            this(executionId, containerId, startedAt, logSink, List.of());
        }

        private ContainerExecution(String executionId, String containerId, Instant startedAt,
                LogSink logSink, List<String> secretsToForget) {
            this.executionId = executionId;
            this.containerId = containerId;
            this.startedAt = startedAt;
            this.secretsToForget = secretsToForget;
            this.logReader = startLogReader(logSink);
        }

        /**
         * Liest den Logstrom waehrend des Laufs.
         *
         * <p>Ein virtueller Thread, weil er fast nur auf Ein- und Ausgabe wartet -- davon
         * kann es viele geben, ohne dass es kostet.
         */
        private Thread startLogReader(LogSink logSink) {
            return Thread.ofVirtual().name("docker-log-" + executionId).start(() -> {
                try (InputStream stream = client.openLogStream(containerId, true)) {
                    DockerLogStreamDecoder.decode(stream, line -> {
                        String clean = redactor.redact(line);
                        if (!clean.isBlank()) {
                            lastLine.set(clean);
                        }
                        logSink.accept(clean);
                    });
                } catch (IOException | DockerApiException e) {
                    // Beim Abbruch wird der Strom geschlossen; das ist kein Fehlerfall.
                    if (!cancelled) {
                        log.debug("Logstrom von {} beendet: {}", shortId(containerId), e.getMessage());
                    }
                }
            });
        }

        @Override
        public String id() {
            return containerId;
        }

        @Override
        public String executionId() {
            return executionId;
        }

        @Override
        public ExecutionResult awaitCompletion(Duration timeout) {
            try {
                int exitCode = client.waitForExit(containerId, timeout);
                logReader.join(Duration.ofSeconds(5));

                if (cancelled) {
                    return finish(ExecutionResult.terminated(executionId, ExecutionStatus.CANCELLED,
                            startedAt, "Abgebrochen"));
                }
                return finish(ExecutionResult.of(executionId, exitCode, startedAt, describeFailure(exitCode)));

            } catch (DockerApiException e) {
                // Zeitlimit ueberschritten: Der Container laeuft noch und muss beendet werden.
                cancel();
                return finish(ExecutionResult.terminated(executionId, ExecutionStatus.TIMEOUT, startedAt,
                        "Zeitlimit von %s ueberschritten".formatted(timeout)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel();
                return finish(ExecutionResult.terminated(executionId, ExecutionStatus.CANCELLED, startedAt,
                        "Warten unterbrochen"));
            }
        }

        /**
         * Speichermangel ist der haeufigste Grund fuer einen scheinbar grundlos
         * abgebrochenen Lauf. Ohne diesen Hinweis sucht man an der falschen Stelle.
         */
        private String describeFailure(int exitCode) {
            if (exitCode == 0) {
                return null;
            }
            boolean outOfMemory = client.inspect(containerId)
                    .map(DockerDto.ContainerDetails::state)
                    .map(DockerDto.ContainerState::wasKilledForMemory)
                    .orElse(false);

            if (outOfMemory) {
                return "Der Container wurde wegen Speichermangels beendet. "
                        + "Das Speicherlimit des Plans ist zu niedrig gesetzt.";
            }
            return lastLine.get();
        }

        @Override
        public void cancel() {
            cancelled = true;
            try {
                client.kill(containerId, "SIGTERM");
                Thread.sleep(GRACE_PERIOD);
                client.kill(containerId, "SIGKILL");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                client.kill(containerId, "SIGKILL");
            } catch (DockerApiException e) {
                log.debug("Abbruch von {}: {}", shortId(containerId), e.getMessage());
            }
        }

        /** Raeumt auf, sobald das Ergebnis feststeht. */
        private ExecutionResult finish(ExecutionResult result) {
            secretsToForget.forEach(redactor::unregister);
            safeRemove(containerId);
            return result;
        }
    }
}
