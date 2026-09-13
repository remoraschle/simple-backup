package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.remo.simplebackup.IntegrationTestBase;
import dev.remo.simplebackup.catalog.CatalogRequests;
import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.MissedRunPolicy;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.catalog.TargetMode;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.LocalProcessExecutor;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.secret.CredentialType;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ein echtes Bucket, ein echter Abzug mit rclone, eine echte Sicherung.
 *
 * <p>Die Gegenstelle ist ein lokaler S3-Server; rclone, restic und die gesamte Verdrahtung
 * sind echt. Ohne diesen Test bliebe offen, ob die Konfigurationsdatei je im Runner ankommt
 * -- und das faellt sonst erst auf, wenn jemand sein Bucket sichern will.
 */
@EnabledIf("toolsAvailable")
@Import(S3SourceEndToEndTest.TestBeans.class)
class S3SourceEndToEndTest extends IntegrationTestBase {

    static boolean toolsAvailable() {
        return available("rclone", "version") && available("restic", "version")
                && available("moto_server", "--help");
    }

    private static boolean available(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static final Path WORKSPACE = createWorkspace();
    private static final Path CONTENT = WORKSPACE.resolve("inhalt");
    private static final Path REPOSITORY = WORKSPACE.resolve("repo");
    private static final Path STAGING = WORKSPACE.resolve("staging");
    private static final Path RESTORE = WORKSPACE.resolve("wiederhergestellt");

    private static final String BUCKET = "meine-daten";
    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY";
    private static final String REPOSITORY_PASSWORD = "ein-sehr-geheimes-repository-passwort";

    private static Process s3Server;
    private static int s3Port;

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount(WORKSPACE.toString(), WORKSPACE.toString(), false, false)));
        }

        @Bean
        @Primary
        BackupExecutor testExecutor() {
            return new LocalProcessExecutor(new SecretRedactor());
        }
    }

    @DynamicPropertySource
    static void stagingDirectory(DynamicPropertyRegistry registry) {
        registry.add("simplebackup.run.staging-directory", STAGING::toString);
    }

    @Autowired
    private RunService runService;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private CredentialService credentials;

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("simple-backup-s3-test");
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    @BeforeEach
    void fillBucket() throws Exception {
        if (s3Server != null) {
            return;
        }
        Files.createDirectories(CONTENT.resolve("unterordner"));
        Files.createDirectories(REPOSITORY);
        Files.writeString(CONTENT.resolve("wichtig.txt"), "Daten aus dem Bucket\n", StandardCharsets.UTF_8);
        Files.writeString(CONTENT.resolve("unterordner/notizen.md"), "# Größer, öfter, über\n",
                StandardCharsets.UTF_8);

        s3Port = freePort();
        s3Server = new ProcessBuilder("moto_server", "-p", String.valueOf(s3Port))
                .redirectErrorStream(true)
                .redirectOutput(WORKSPACE.resolve("moto.log").toFile())
                .start();

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(this::s3Reachable);

        // Befuellt wird mit rclone selbst -- so braucht der Test keinen zweiten S3-Client.
        rclone("mkdir", "gegenstelle:" + BUCKET);
        rclone("copy", CONTENT.toString(), "gegenstelle:" + BUCKET);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (s3Server != null) {
            s3Server.destroy();
        }
        if (!Files.exists(WORKSPACE)) {
            return;
        }
        try (var walk = Files.walk(WORKSPACE)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Aufraeumen im Test darf nicht zum Fehler werden.
                }
            });
        }
    }

    private boolean s3Reachable() throws IOException {
        try (var socket = new java.net.Socket("127.0.0.1", s3Port)) {
            return socket.isConnected();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** rclone fuer die Vorbereitung: dieselbe Gegenstelle, eigene Konfiguration. */
    private void rclone(String... arguments) throws Exception {
        Path config = WORKSPACE.resolve("test-rclone.conf");
        Files.writeString(config, """
                [gegenstelle]
                type = s3
                provider = Other
                env_auth = false
                access_key_id = %s
                secret_access_key = %s
                endpoint = http://127.0.0.1:%d
                region = us-east-1
                """.formatted(ACCESS_KEY, SECRET_KEY, s3Port));

        var command = new java.util.ArrayList<>(List.of("rclone", "--config", config.toString()));
        command.addAll(List.of(arguments));

        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        // rclone kommt mit einem gesetzten AWS_CA_BUNDLE nicht zurecht; in einer
        // Entwicklungsumgebung hinter einem Proxy ist es gesetzt.
        builder.environment().remove("AWS_CA_BUNDLE");

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());

        if (process.waitFor() != 0) {
            throw new IllegalStateException("rclone " + String.join(" ", arguments) + ": " + output);
        }
    }

    @Test
    @DisplayName("Ein Bucket wird abgezogen, gesichert und kommt unveraendert zurueck")
    void fetchesBucketAndBacksItUp() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        var repositoryPassword = credentials.create("repo-" + unique,
                CredentialType.RESTIC_REPOSITORY_PASSWORD, null, REPOSITORY_PASSWORD);
        var keys = credentials.create("s3-" + unique, CredentialType.S3_KEYPAIR, null,
                "{\"accessKeyId\":\"%s\",\"secretAccessKey\":\"%s\"}".formatted(ACCESS_KEY, SECRET_KEY));

        var source = catalog.createSource(new CatalogRequests.SaveSource("bucket-" + unique, null,
                new SourceConfig.S3("http://127.0.0.1:" + s3Port, BUCKET, null, null, keys.id())));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget("ziel-" + unique, null,
                TargetMode.RESTIC,
                new TargetConfig.LocalPath(REPOSITORY.toString(), repositoryPassword.id()), true));

        UUID planId = catalog.createPlan(new CatalogRequests.SavePlan("plan-" + unique, null,
                source.id(), List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.NEVER, null)).id();

        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        await().atMost(Duration.ofMinutes(3))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        assertThat(runService.findRun(runId).getStatus())
                .withFailMessage("Der Lauf ist gescheitert:%n%s", runService.readLog(runId))
                .isEqualTo(RunStatus.SUCCESS);

        // Zurueckholen und vergleichen -- der einzige Beweis, der zaehlt.
        Files.createDirectories(RESTORE);
        assertThat(restic("restore", "latest", "--target", RESTORE.toString())).isZero();

        Path restored = findFile(RESTORE, "wichtig.txt");
        assertThat(restored).isNotNull();
        assertThat(Files.readString(restored)).isEqualTo("Daten aus dem Bucket\n");

        Path notes = findFile(RESTORE, "notizen.md");
        assertThat(notes).isNotNull();
        assertThat(Files.readString(notes)).contains("Größer, öfter, über");

        // Die Zugangsdaten stehen in der Konfigurationsdatei, nicht in der Kommandozeile --
        // und damit auch nicht im Protokoll.
        assertThat(runService.readLog(runId)).doesNotContain(SECRET_KEY);

        // Und der Zwischenstand ist weg.
        assertThat(Files.exists(STAGING.resolve("plan-" + planId))).isFalse();
    }

    private static Path findFile(Path root, String name) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst().orElse(null);
        }
    }

    private int restic(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("restic"));
        command.addAll(List.of(arguments));

        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("RESTIC_REPOSITORY", REPOSITORY.toString());
        builder.environment().put("RESTIC_PASSWORD", REPOSITORY_PASSWORD);

        Process process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }
}
