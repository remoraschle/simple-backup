package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionStatus;
import dev.remo.simplebackup.engine.LogSink;
import dev.remo.simplebackup.engine.ResourceLimits;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DockerJobExecutorTest {

    private static final String CONTAINER_ID = "c0ffee123456";

    private FakeDockerApi api;
    private DockerJobExecutor executor;
    private List<String> logLines;

    @BeforeEach
    void setUp() {
        api = new FakeDockerApi();
        api.respond("/containers/create", 201, """
                {"Id":"%s","Warnings":[]}""".formatted(CONTAINER_ID));
        api.respond("/images/json", 200, """
                [{"RepoTags":["runner:1.0"]}]""");
        api.respondRaw("/containers/" + CONTAINER_ID + "/logs", 200, new byte[0]);

        var properties = new DockerProperties(api.baseUrl(), "v1.51", Duration.ofSeconds(2),
                Duration.ofSeconds(5), "runner:1.0", "1000:1000");
        executor = new DockerJobExecutor(
                new DockerApiClient(properties, new ObjectMapper()), properties, new SecretRedactor());
        logLines = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    private static ExecutionRequest request() {
        return ExecutionRequest.builder("runner:1.0", "restic", "backup", "/quelle")
                .executionId("lauf-42")
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private void exits(int code) {
        api.respond("/containers/" + CONTAINER_ID + "/wait", 200, """
                {"StatusCode":%d}""".formatted(code));
    }

    private static byte[] logFrames(String... lines) {
        var out = new ByteArrayOutputStream();
        for (String line : lines) {
            byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(1);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
            out.writeBytes(data);
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("Start")
    class Start {

        @Test
        @DisplayName("Geheimnisse werden vor dem Start hineinkopiert, nicht danach")
        void copiesSecretsBeforeStarting() {
            // Die Reihenfolge entscheidet: Nach dem Start liefe das Kommando bereits,
            // waehrend die Dateien noch fehlten.
            exits(0);
            var withSecret = ExecutionRequest.builder("runner:1.0", "restic", "backup")
                    .executionId("lauf-42")
                    .secretFile("restic-password", "geheim-geheim")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            executor.start(withSecret, logLines::add).awaitCompletion(Duration.ofSeconds(5));

            var paths = api.requests().stream().map(FakeDockerApi.Recorded::path).toList();
            assertThat(paths).containsSubsequence(
                    "/containers/create",
                    "/containers/" + CONTAINER_ID + "/archive",
                    "/containers/" + CONTAINER_ID + "/start");
        }

        @Test
        @DisplayName("Jeder Container traegt Labels zur Wiedererkennung")
        void labelsEveryContainer() {
            // Ohne sie gaebe es kein Wiederanhaengen und kein Aufraeumen verwaister Container.
            exits(0);
            executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(api.lastRequestTo("/containers/create").bodyAsString())
                    .contains("\"simple-backup.managed-by\":\"simple-backup\"")
                    .contains("\"simple-backup.execution-id\":\"lauf-42\"");
        }

        @Test
        @DisplayName("AutoRemove bleibt aus, sonst gingen Rueckgabewert und Log verloren")
        void neverUsesAutoRemove() {
            exits(0);
            executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(api.lastRequestTo("/containers/create").bodyAsString())
                    .contains("\"AutoRemove\":false");
        }

        @Test
        @DisplayName("Der Container darf keine Rechte hinzugewinnen")
        void dropsPrivilegeEscalation() {
            exits(0);
            executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(api.lastRequestTo("/containers/create").bodyAsString())
                    .contains("no-new-privileges")
                    .contains("\"User\":\"1000:1000\"");
        }

        @Test
        @DisplayName("Ressourcengrenzen werden in die Einheiten der API umgerechnet")
        void translatesResourceLimits() {
            exits(0);
            var limited = ExecutionRequest.builder("runner:1.0", "restic")
                    .executionId("lauf-42")
                    .limits(new ResourceLimits(512L * 1024 * 1024, 1.5, "none"))
                    .mount(VolumeMount.readOnlyPath("/srv/fotos", "/quelle"))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            executor.start(limited, logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(api.lastRequestTo("/containers/create").bodyAsString())
                    .contains("\"Memory\":536870912")
                    // 1,5 Kerne entsprechen 1.500.000.000 Nano-CPUs.
                    .contains("\"NanoCpus\":1500000000")
                    .contains("\"NetworkMode\":\"none\"")
                    .contains("\"Binds\":[\"/srv/fotos:/quelle:ro\"]");
        }

        @Test
        @DisplayName("Ein fehlendes Image wird vor dem Lauf geladen")
        void pullsMissingImage() {
            // Vorab und nicht mitten im Lauf: Sonst braeche ein Backup nach zwei Stunden ab.
            api.respond("/images/json", 200, "[]");
            exits(0);

            executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(api.requests().stream().map(FakeDockerApi.Recorded::path))
                    .contains("/images/create");
        }

        @Test
        @DisplayName("Ein nicht ladbares Image nennt den Befehl zum Vorabladen")
        void explainsUnavailableImage() {
            api.respond("/images/json", 200, "[]");
            api.respond("/images/create", 500, """
                    {"message":"no route to host"}""");

            assertThatThrownBy(() -> executor.start(request(), logLines::add))
                    .isInstanceOf(ExecutionException.class)
                    .hasMessageContaining("docker pull runner:1.0");
        }

        @Test
        @DisplayName("Scheitert der Start, bleibt kein halb angelegter Container zurueck")
        void removesContainerWhenStartFails() {
            api.respond("/containers/" + CONTAINER_ID + "/start", 500, """
                    {"message":"driver failed"}""");

            assertThatThrownBy(() -> executor.start(request(), logLines::add))
                    .isInstanceOf(ExecutionException.class);

            assertThat(api.requests())
                    .anySatisfy(recorded -> {
                        assertThat(recorded.method()).isEqualTo("DELETE");
                        assertThat(recorded.path()).isEqualTo("/containers/" + CONTAINER_ID);
                    });
        }
    }

    @Nested
    @DisplayName("Ergebnis")
    class Outcome {

        @Test
        @DisplayName("Rueckgabewert 0 ergibt SUCCESS, und der Container wird entfernt")
        void successRemovesContainer() {
            exits(0);

            var result = executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(result.exitCode()).isZero();
            assertThat(result.executionId()).isEqualTo("lauf-42");
            assertThat(api.requests()).anySatisfy(recorded ->
                    assertThat(recorded.method()).isEqualTo("DELETE"));
        }

        @Test
        @DisplayName("Ein Fehlschlag traegt die letzte Ausgabezeile als Hinweis")
        void failureCarriesLastLine() {
            exits(1);
            api.respondRaw("/containers/" + CONTAINER_ID + "/logs", 200,
                    logFrames("scanning", "Fatal: repository is already locked"));
            api.respond("/containers/" + CONTAINER_ID + "/json", 200, """
                    {"Id":"%s","State":{"Status":"exited","Running":false,"ExitCode":1},
                     "Config":{"Labels":{}},"Mounts":[]}""".formatted(CONTAINER_ID));

            var result = executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.lastError()).isEqualTo("Fatal: repository is already locked");
            assertThat(logLines).containsExactly("scanning", "Fatal: repository is already locked");
        }

        @Test
        @DisplayName("Speichermangel wird benannt statt als unerklaerlicher Abbruch gemeldet")
        void explainsOutOfMemoryKill() {
            // Der haeufigste Grund fuer einen scheinbar grundlos abgebrochenen Lauf. Ohne
            // diesen Hinweis sucht man an der falschen Stelle.
            exits(137);
            api.respond("/containers/" + CONTAINER_ID + "/json", 200, """
                    {"Id":"%s","State":{"Status":"exited","Running":false,"ExitCode":137,"OOMKilled":true},
                     "Config":{"Labels":{}},"Mounts":[]}""".formatted(CONTAINER_ID));

            var result = executor.start(request(), logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.lastError()).contains("Speichermangels").contains("Speicherlimit");
        }

        @Test
        @DisplayName("Geheimnisse werden aus der Ausgabe entfernt")
        void redactsSecretsInOutput() {
            exits(0);
            api.respondRaw("/containers/" + CONTAINER_ID + "/logs", 200,
                    logFrames("verwende Passwort geheim-geheim-123"));

            var withSecret = ExecutionRequest.builder("runner:1.0", "restic")
                    .executionId("lauf-42")
                    .secretFile("restic-password", "geheim-geheim-123")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            executor.start(withSecret, logLines::add).awaitCompletion(Duration.ofSeconds(5));

            assertThat(String.join("\n", logLines)).doesNotContain("geheim-geheim-123").contains("***");
        }
    }

    @Nested
    @DisplayName("Wiederanhaengen")
    class Reattaching {

        @Test
        @DisplayName("Ein noch laufender Container wird ueber sein Label wiedergefunden")
        void findsRunningContainerByLabel() {
            // Ohne das waere ein vierstuendiger Lauf durch einen Backend-Neustart verloren.
            api.respond("/containers/json", 200, """
                    [{"Id":"%s","State":"running",
                      "Labels":{"simple-backup.execution-id":"lauf-42"}}]""".formatted(CONTAINER_ID));

            var running = executor.reattach("lauf-42", LogSink.discarding());

            assertThat(running).isPresent();
            assertThat(running.orElseThrow().id()).isEqualTo(CONTAINER_ID);
            assertThat(running.orElseThrow().executionId()).isEqualTo("lauf-42");
        }

        @Test
        @DisplayName("Ohne passenden Container wird nichts behauptet")
        void returnsEmptyWhenNothingMatches() {
            api.respond("/containers/json", 200, "[]");

            assertThat(executor.reattach("lauf-42", LogSink.discarding())).isEmpty();
        }

        @Test
        @DisplayName("Ein wiedergefundener Lauf liefert weiterhin sein Ergebnis")
        void reattachedExecutionStillYieldsResult() {
            api.respond("/containers/json", 200, """
                    [{"Id":"%s","State":"running",
                      "Labels":{"simple-backup.execution-id":"lauf-42"}}]""".formatted(CONTAINER_ID));
            exits(0);

            var result = executor.reattach("lauf-42", LogSink.discarding())
                    .orElseThrow()
                    .awaitCompletion(Duration.ofSeconds(5));

            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        }
    }
}
