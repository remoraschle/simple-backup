package dev.remo.simplebackup.engine;

import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fuehrt Schritte als Kindprozess des Backends aus.
 *
 * <p><b>Fuer Tests und die lokale Entwicklung ohne Docker-Daemon.</b> Im Betrieb kommt
 * {@code DockerJobExecutor} zum Einsatz. Die Unterschiede sind bewusst keine Feinheiten:
 *
 * <ul>
 *   <li><b>Keine Isolierung.</b> Einhaengungen werden ignoriert, der Prozess sieht das
 *       Dateisystem des Backends unmittelbar. Ressourcengrenzen werden nicht durchgesetzt.</li>
 *   <li><b>Kein Wiederanhaengen.</b> Die Prozesse sind Kinder des Backends und sterben mit
 *       ihm. {@link #reattach} liefert deshalb immer leer -- genau dieser Unterschied ist
 *       einer der Gruende fuer das Container-Modell.</li>
 * </ul>
 *
 * <p>Gemeinsam ist beiden das Wesentliche: Es gibt keine Shell. Das Kommando wird als
 * Argumentliste an {@link ProcessBuilder} uebergeben, sodass Sonderzeichen in Pfaden oder
 * Hostnamen Argumente bleiben statt zu Befehlen zu werden.
 */
public class LocalProcessExecutor implements BackupExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessExecutor.class);

    /** Frist zwischen freundlichem und erzwungenem Beenden. */
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(10);

    private final SecretRedactor redactor;

    /**
     * Was aus der Umgebung des Backends uebernommen wird.
     *
     * <p>Mehr braucht kein Werkzeug: Der Pfad, um es zu finden, das Heimatverzeichnis fuer
     * Git und die Angaben zu Sprache und Zeitzone, damit Ausgaben und Zeitstempel stimmen.
     */
    private static final Set<String> PASSED_THROUGH =
            Set.of("PATH", "HOME", "TMPDIR", "TZ", "LANG", "LC_ALL", "USER", "SHELL");

    public LocalProcessExecutor(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    @Override
    public RunningExecution start(ExecutionRequest request, LogSink logSink) {
        Instant startedAt = Instant.now();
        Path secretsDirectory = null;

        try {
            secretsDirectory = writeSecrets(request);
            final Path secretsPath = secretsDirectory;

            // Der Aufrufer nennt Geheimnisdateien unter /run/secrets, weil sie dort im
            // Container liegen. Hier gibt es dieses Verzeichnis nicht -- der Pfad wird
            // deshalb in Kommando und Umgebung auf das temporaere Verzeichnis umgebogen.
            //
            // Ohne diese Uebersetzung liefe ein Werkzeug, das seine Zugangsdaten aus einer
            // Datei liest, unweigerlich ins Leere.
            ProcessBuilder builder = new ProcessBuilder(rewriteSecretPaths(request.command(), secretsPath));

            // Die Umgebung wird auf das Noetigste eingedampft, statt die des Backends zu
            // erben. Ein Container startet mit der Umgebung seines Images; ein Kindprozess
            // erbt sonst alles, was zufaellig gesetzt ist -- Proxy-Angaben, CA-Pfade,
            // AWS-Variablen. Werkzeuge lesen genau solche Variablen mit, und dann verhaelt
            // sich der Entwicklungsbetrieb anders als der Ernstfall.
            builder.environment().keySet().retainAll(PASSED_THROUGH);

            request.environment().forEach((name, value) ->
                    builder.environment().put(name, rewriteSecretPath(value, secretsPath)));

            if (secretsPath != null) {
                builder.environment().put("SIMPLEBACKUP_SECRETS_DIR", secretsPath.toString());
            }
            // Beide Stroeme zusammen: Viele Werkzeuge melden Fortschritt auf dem einen und
            // Fehler auf dem anderen Strom, ohne sich an eine Regel zu halten.
            builder.redirectErrorStream(true);

            Process process = builder.start();
            return new LocalRunningExecution(request, process, startedAt, secretsDirectory, logSink);

        } catch (IOException e) {
            deleteRecursively(secretsDirectory);
            throw new ExecutionException(
                    "Schritt %s liess sich nicht starten: %s".formatted(request.executionId(), e.getMessage()), e);
        }
    }

    /** Kindprozesse hinterlassen nichts, was aufzuraeumen waere: Sie sterben mit dem Backend. */
    @Override
    public java.util.Set<String> reapOrphans(java.util.Set<String> knownExecutionIds) {
        return java.util.Set.of();
    }

    /** Ohne Container gibt es keine Umgebung; das Feld bleibt nur der Vollstaendigkeit halber gefuellt. */
    @Override
    public String defaultEnvironment() {
        return "kindprozess";
    }

    /**
     * Liefert immer leer: Kindprozesse ueberleben den Neustart des Backends nicht.
     *
     * <p>Das ist keine Luecke in der Umsetzung, sondern die Eigenschaft, die das
     * Container-Modell im Betrieb rechtfertigt.
     */
    @Override
    public Optional<RunningExecution> reattach(String executionId, LogSink logSink) {
        return Optional.empty();
    }

    /**
     * Legt die Geheimnisse in einem Verzeichnis ab, das nur der eigene Benutzer lesen kann.
     *
     * <p>Nicht als Umgebungsvariable und nicht als Argument: Argumente sind fuer jeden in
     * {@code ps} sichtbar.
     */
    private Path writeSecrets(ExecutionRequest request) throws IOException {
        if (request.secretFiles().isEmpty()) {
            return null;
        }

        Path directory = Files.createTempDirectory("simple-backup-secrets-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        var fileAttributes = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        for (Map.Entry<String, String> secret : request.secretFiles().entrySet()) {
            Path file = directory.resolve(secret.getKey());
            Files.createFile(file, fileAttributes);
            Files.writeString(file, secret.getValue(), StandardCharsets.UTF_8);
            redactor.register(secret.getValue());
        }
        return directory;
    }

    private static List<String> rewriteSecretPaths(List<String> arguments, Path secretsDirectory) {
        return arguments.stream().map(argument -> rewriteSecretPath(argument, secretsDirectory)).toList();
    }

    private static String rewriteSecretPath(String value, Path secretsDirectory) {
        if (secretsDirectory == null || value == null
                || !value.contains(ExecutionRequest.SECRETS_DIRECTORY)) {
            return value;
        }
        return value.replace(ExecutionRequest.SECRETS_DIRECTORY, secretsDirectory.toString());
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Temporaere Datei nicht loeschbar: {}", path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final class LocalRunningExecution implements RunningExecution {

        private final ExecutionRequest request;
        private final Process process;
        private final Instant startedAt;
        private final Path secretsDirectory;
        private final AtomicReference<String> lastLine = new AtomicReference<>();
        private final Thread logReader;
        private volatile boolean cancelled;

        private LocalRunningExecution(ExecutionRequest request, Process process, Instant startedAt,
                Path secretsDirectory, LogSink logSink) {
            this.request = request;
            this.process = process;
            this.startedAt = startedAt;
            this.secretsDirectory = secretsDirectory;
            this.logReader = startLogReader(logSink);
        }

        /**
         * Liest die Ausgabe waehrend des Laufs, nicht danach.
         *
         * <p>Ein virtueller Thread, weil er fast ausschliesslich auf Ein-/Ausgabe wartet.
         * Wuerde erst nach Prozessende gelesen, liefe bei gesprächigen Werkzeugen der
         * Ausgabepuffer des Betriebssystems voll und der Prozess bliebe stehen.
         */
        private Thread startLogReader(LogSink logSink) {
            return Thread.ofVirtual().name("log-" + request.executionId()).start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String clean = redactor.redact(line);
                        if (!clean.isBlank()) {
                            lastLine.set(clean);
                        }
                        logSink.accept(clean);
                    }
                } catch (IOException e) {
                    // Beim Abbruch wird der Strom geschlossen; das ist kein Fehlerfall.
                    if (!cancelled) {
                        log.debug("Lesen der Ausgabe von {} beendet: {}", request.executionId(), e.getMessage());
                    }
                }
            });
        }

        @Override
        public String id() {
            return String.valueOf(process.pid());
        }

        @Override
        public String executionId() {
            return request.executionId();
        }

        @Override
        public ExecutionResult awaitCompletion(Duration timeout) {
            try {
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

                if (!finished) {
                    cancel();
                    return finish(ExecutionResult.terminated(request.executionId(), ExecutionStatus.TIMEOUT,
                            startedAt, "Zeitlimit von %s ueberschritten".formatted(timeout)));
                }

                // Kurz auf den Leser warten, damit die letzten Zeilen noch ankommen.
                logReader.join(Duration.ofSeconds(5));

                if (cancelled) {
                    return finish(ExecutionResult.terminated(request.executionId(), ExecutionStatus.CANCELLED,
                            startedAt, "Abgebrochen"));
                }
                return finish(ExecutionResult.of(request.executionId(), process.exitValue(), startedAt,
                        lastLine.get()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel();
                return finish(ExecutionResult.terminated(request.executionId(), ExecutionStatus.CANCELLED,
                        startedAt, "Warten unterbrochen"));
            }
        }

        @Override
        public void cancel() {
            if (!process.isAlive()) {
                return;
            }
            cancelled = true;

            // Erst die Kinder, dann den Prozess selbst -- sonst laufen Enkelprozesse weiter.
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();

            try {
                if (!process.waitFor(GRACE_PERIOD.toSeconds(), TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        /** Raeumt auf, sobald das Ergebnis feststeht. Geheimnisse leben nicht laenger als der Lauf. */
        private ExecutionResult finish(ExecutionResult result) {
            if (secretsDirectory != null) {
                for (String secret : request.secretFiles().values()) {
                    redactor.unregister(secret);
                }
                deleteRecursively(secretsDirectory);
            }
            return result;
        }
    }
}
