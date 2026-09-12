package dev.remo.simplebackup.run;

import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.ExecutionStatus;
import dev.remo.simplebackup.engine.LogSink;
import dev.remo.simplebackup.engine.RunningExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Eine Attrappe des Ausfuehrers fuer die Tests des Runners.
 *
 * <p>Hier ist eine Attrappe richtig: Geprueft wird die Orchestrierung -- welche Kommandos in
 * welcher Reihenfolge, mit welchen Einhaengungen und Geheimnissen, und wie aus den Ergebnissen
 * ein Gesamtzustand wird. Echte restic-Laeufe wuerden davon nichts zusaetzlich zeigen, dafuer
 * aber restic, ein Repository und Minuten Laufzeit verlangen.
 */
class FakeBackupExecutor implements BackupExecutor {

    /** Eine Regel, wie auf eine bestimmte Anfrage geantwortet wird. */
    private record Rule(Predicate<ExecutionRequest> matches, int exitCode, List<String> output) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final List<ExecutionRequest> requests = new ArrayList<>();

    /** Standardantwort, wenn keine Regel greift: Erfolg ohne Ausgabe. */
    private int defaultExitCode;

    FakeBackupExecutor whenCommandContains(String argument, int exitCode, String... output) {
        rules.add(new Rule(request -> request.command().contains(argument), exitCode, List.of(output)));
        return this;
    }

    /**
     * Antwortet abhaengig vom Ziel-Repository.
     *
     * <p>Noetig, weil das Ziel nicht im Kommando steht, sondern in RESTIC_REPOSITORY. Eine
     * Regel auf das Kommando allein kann zwei Ziele nicht unterscheiden -- ein Test, der es
     * dennoch versuchte, waere gruen, ohne etwas zu pruefen.
     */
    FakeBackupExecutor whenRepositoryContains(String fragment, int exitCode, String... output) {
        rules.add(new Rule(
                request -> String.valueOf(request.environment().get("RESTIC_REPOSITORY")).contains(fragment),
                exitCode, List.of(output)));
        return this;
    }

    FakeBackupExecutor defaultExitCode(int exitCode) {
        this.defaultExitCode = exitCode;
        return this;
    }

    List<ExecutionRequest> requests() {
        return List.copyOf(requests);
    }

    List<List<String>> commands() {
        return requests.stream().map(ExecutionRequest::command).toList();
    }

    ExecutionRequest requestContaining(String argument) {
        return requests.stream()
                .filter(request -> request.command().contains(argument))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Kein Kommando mit '%s'. Ausgefuehrt: %s".formatted(argument, commands())));
    }

    @Override
    public RunningExecution start(ExecutionRequest request, LogSink logSink) {
        requests.add(request);

        Rule rule = rules.stream().filter(candidate -> candidate.matches().test(request))
                .findFirst().orElse(null);

        int exitCode = rule == null ? defaultExitCode : rule.exitCode();
        List<String> output = rule == null ? List.of() : rule.output();

        output.forEach(logSink::accept);

        return new RunningExecution() {
            private final Instant startedAt = Instant.now();

            @Override
            public String id() {
                return "fake-" + request.executionId();
            }

            @Override
            public String executionId() {
                return request.executionId();
            }

            @Override
            public ExecutionResult awaitCompletion(Duration timeout) {
                return ExecutionResult.of(request.executionId(), exitCode, startedAt,
                        exitCode == 0 ? null : output.isEmpty() ? "Fehlgeschlagen" : output.getLast());
            }

            @Override
            public void cancel() {
                // Nichts zu tun.
            }
        };
    }

    @Override
    public java.util.Set<String> reapOrphans(java.util.Set<String> knownExecutionIds) {
        return java.util.Set.of();
    }

    @Override
    public String defaultEnvironment() {
        return "runner:1.0";
    }

    @Override
    public Optional<RunningExecution> reattach(String executionId, LogSink logSink) {
        return Optional.empty();
    }

    /** Fuer den Fall, dass ein Ergebnis ohne regulaeren Rueckgabewert endet. */
    static ExecutionResult terminated(String executionId, ExecutionStatus status) {
        return ExecutionResult.terminated(executionId, status, Instant.now(), "Abgebrochen");
    }
}
