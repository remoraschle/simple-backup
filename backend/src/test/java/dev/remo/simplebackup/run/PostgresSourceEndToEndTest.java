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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
 * Eine echte Datenbank, ein echter Dump, eine echte Sicherung.
 *
 * <p>Der Punkt dieses Tests ist der Beweis, dass am Ende etwas Einspielbares im Repository
 * liegt -- nicht, dass ein Kommando gebaut wurde. Ein Dump, den niemand je gelesen hat, ist
 * eine Vermutung.
 *
 * <p>Laeuft nur, wo die PostgreSQL-Werkzeuge vorhanden sind, und nutzt dieselbe Datenbank,
 * gegen die auch die uebrigen Tests laufen.
 */
@EnabledIf("postgresToolsAvailable")
@Import(PostgresSourceEndToEndTest.TestBeans.class)
class PostgresSourceEndToEndTest extends IntegrationTestBase {

    static boolean postgresToolsAvailable() {
        try {
            return new ProcessBuilder("pg_dump", "--version").start().waitFor() == 0
                    && new ProcessBuilder("psql", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static final Path WORKSPACE = createWorkspace();
    private static final Path REPOSITORY = WORKSPACE.resolve("repo");
    private static final Path STAGING = WORKSPACE.resolve("staging");
    private static final Path RESTORE = WORKSPACE.resolve("wiederhergestellt");

    private static final String REPOSITORY_PASSWORD = "ein-sehr-geheimes-repository-passwort";

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount(WORKSPACE.toString(), WORKSPACE.toString(), false, false)));
        }

        /**
         * Echte Prozesse. Die lokal vorhandenen Werkzeuge werden verwendet; das Image, das
         * im Betrieb die Hauptversion traegt, ist hier ohne Bedeutung.
         */
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
            return Files.createTempDirectory("simple-backup-postgres-test");
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
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

    /** Die Zugangsdaten der Testdatenbank, so wie die Basisklasse sie gesetzt hat. */
    private String property(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String databaseName() {
        String url = property("SIMPLEBACKUP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/simplebackup_test");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private UUID createPlanWithTable(String tableName) throws Exception {
        String url = property("SIMPLEBACKUP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/simplebackup_test");
        String user = property("SIMPLEBACKUP_TEST_DB_USER", "simplebackup");
        String password = property("SIMPLEBACKUP_TEST_DB_PASSWORD", "simplebackup");

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {

            statement.execute("DROP TABLE IF EXISTS " + tableName);
            statement.execute("CREATE TABLE " + tableName + " (id int primary key, text text)");
            statement.execute("INSERT INTO " + tableName + " VALUES (1, 'Größer, öfter, über')");
        }

        Files.createDirectories(REPOSITORY);

        String unique = UUID.randomUUID().toString().substring(0, 8);
        var repositoryPassword = credentials.create("repo-" + unique,
                CredentialType.RESTIC_REPOSITORY_PASSWORD, null, REPOSITORY_PASSWORD);
        var databasePassword = credentials.create("db-" + unique, CredentialType.PASSWORD, null, password);

        var source = catalog.createSource(new CatalogRequests.SaveSource("pg-" + unique, null,
                new SourceConfig.Postgres("localhost", 5432, 16, List.of(databaseName()), user,
                        databasePassword.id(), false)));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget("ziel-" + unique, null,
                TargetMode.RESTIC,
                new TargetConfig.LocalPath(REPOSITORY.toString(), repositoryPassword.id()), true));

        return catalog.createPlan(new CatalogRequests.SavePlan("plan-" + unique, null, source.id(),
                List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.NEVER, null)).id();
    }

    @Test
    @DisplayName("Eine Datenbank wird gedumpt, geprueft, gesichert und ist wieder einspielbar")
    void dumpsVerifiesBacksUpAndRestores() throws Exception {
        String table = "sicherungstest_" + UUID.randomUUID().toString().substring(0, 8);
        UUID planId = createPlanWithTable(table);

        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        await().atMost(Duration.ofMinutes(3))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        BackupRun run = runService.findRun(runId);

        assertThat(run.getStatus())
                .withFailMessage("Der Lauf ist gescheitert:%n%s", runService.readLog(runId))
                .isEqualTo(RunStatus.SUCCESS);

        // Die Schritte erzaehlen den Ablauf: erst beschaffen, dann pruefen, dann sichern,
        // am Ende aufraeumen.
        assertThat(run.getSteps()).extracting(RunStep::getKind)
                .containsExactly(StepKind.ACQUIRE, StepKind.VERIFY, StepKind.PREPARE,
                        StepKind.PREPARE, StepKind.TRANSFER, StepKind.CLEANUP);

        // Der Zwischenstand ist weg: Ein liegengebliebener Dump waere unverschluesselter
        // Klartext auf der Platte.
        assertThat(Files.exists(STAGING.resolve("plan-" + planId))).isFalse();

        // Und jetzt die eigentliche Frage: Steht der Dump im Repository, und laesst er sich
        // lesen? Dafuer wird er zurueckgeholt und pg_restore vorgelegt.
        Files.createDirectories(RESTORE);
        assertThat(restic("restore", "latest", "--target", RESTORE.toString())).isZero();

        Path dump = findDump(RESTORE);
        assertThat(dump).isNotNull();

        var listing = new ProcessBuilder("pg_restore", "--list", dump.toString())
                .redirectErrorStream(true).start();
        String toc = new String(listing.getInputStream().readAllBytes());

        assertThat(listing.waitFor())
                .withFailMessage("pg_restore kam mit dem Dump nicht zurecht:%n%s", toc)
                .isZero();
        assertThat(toc).contains(table);
    }

    private static Path findDump(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".dump"))
                    .findFirst()
                    .orElse(null);
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
