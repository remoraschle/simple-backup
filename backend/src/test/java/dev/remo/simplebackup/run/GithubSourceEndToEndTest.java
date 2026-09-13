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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Ein echtes Repository wird gespiegelt und gesichert.
 *
 * <p>Gegen ein lokales Git-Repository statt gegen GitHub: Der Teil, auf den es ankommt, ist
 * {@code git clone --mirror} und was danach im Snapshot liegt. Die API selbst wird von einem
 * Testserver nachgestellt -- ein Test, der ein Konto und Netz braucht, laeuft nirgends.
 */
@EnabledIf("gitAvailable")
@Import(GithubSourceEndToEndTest.TestBeans.class)
class GithubSourceEndToEndTest extends IntegrationTestBase {

    static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static final Path WORKSPACE = createWorkspace();
    private static final Path ORIGIN = WORKSPACE.resolve("herkunft");
    private static final Path REPOSITORY = WORKSPACE.resolve("repo");
    private static final Path STAGING = WORKSPACE.resolve("staging");
    private static final Path RESTORE = WORKSPACE.resolve("wiederhergestellt");

    private static final String REPOSITORY_PASSWORD = "ein-sehr-geheimes-repository-passwort";
    private static final String TOKEN = "ghp_einbesonderergeheimertoken";

    private static FakeGitHub github;

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
    static void properties(DynamicPropertyRegistry registry) {
        github = new FakeGitHub();
        registry.add("simplebackup.run.staging-directory", STAGING::toString);
        registry.add("simplebackup.run.github-api-url", github::baseUrl);
    }

    @Autowired
    private RunService runService;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private CredentialService credentials;

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("simple-backup-github-test");
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    @BeforeEach
    void prepareOriginRepository() throws Exception {
        if (Files.exists(ORIGIN)) {
            return;
        }
        Files.createDirectories(ORIGIN);
        Files.createDirectories(REPOSITORY);

        git(ORIGIN, "init", "--initial-branch=main", ".");
        git(ORIGIN, "config", "user.email", "test@example.invalid");
        git(ORIGIN, "config", "user.name", "Test");

        Files.writeString(ORIGIN.resolve("README.md"), "# Größer, öfter, über\n", StandardCharsets.UTF_8);
        git(ORIGIN, "add", "README.md");
        git(ORIGIN, "commit", "-m", "Erster Stand");
        git(ORIGIN, "tag", "v1.0");

        // Ein zweiter Zweig: Ein Mirror muss ihn mitnehmen, ein Checkout nicht.
        git(ORIGIN, "branch", "nebenzweig");
    }

    @AfterEach
    void resetApi() {
        // Der Testserver lebt fuer die ganze Klasse; die Antworten setzt jeder Test selbst.
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (github != null) {
            github.close();
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

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());

        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + ": " + output);
        }
    }

    private UUID createPlan(boolean withMetadata) {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        github.respond("/users/remo/repos?per_page=100&page=1", """
                [{"name":"werkzeug","full_name":"remo/werkzeug","clone_url":"%s","fork":false,
                  "archived":false}]""".formatted(ORIGIN.toUri()))
                .respond("/repos/remo/werkzeug/issues?state=all&per_page=100",
                        "[{\"number\":1,\"title\":\"Etwas ist kaputt\"}]")
                .respond("/repos/remo/werkzeug/releases?per_page=100",
                        "[{\"tag_name\":\"v1.0\",\"name\":\"Erste Fassung\"}]");

        var repositoryPassword = credentials.create("repo-" + unique,
                CredentialType.RESTIC_REPOSITORY_PASSWORD, null, REPOSITORY_PASSWORD);
        var token = credentials.create("token-" + unique, CredentialType.API_TOKEN, null, TOKEN);

        var source = catalog.createSource(new CatalogRequests.SaveSource("github-" + unique, null,
                new SourceConfig.GitHub("remo", List.of(), false, withMetadata, token.id())));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget("ziel-" + unique, null,
                TargetMode.RESTIC,
                new TargetConfig.LocalPath(REPOSITORY.toString(), repositoryPassword.id()), true));

        return catalog.createPlan(new CatalogRequests.SavePlan("plan-" + unique, null, source.id(),
                List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.NEVER, null)).id();
    }

    private BackupRun runPlan(UUID planId) {
        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        await().atMost(Duration.ofMinutes(3))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        BackupRun run = runService.findRun(runId);
        assertThat(run.getStatus())
                .withFailMessage("Der Lauf ist gescheitert:%n%s", runService.readLog(runId))
                .isEqualTo(RunStatus.SUCCESS);
        return run;
    }

    @Test
    @DisplayName("Ein Repository wird gespiegelt, gesichert und ist wieder klonbar")
    void mirrorsAndBacksUpARepository() throws Exception {
        UUID planId = createPlan(true);
        BackupRun run = runPlan(planId);

        assertThat(run.getSteps()).extracting(RunStep::getKind)
                .contains(StepKind.ACQUIRE, StepKind.TRANSFER, StepKind.CLEANUP);

        // Zurueckholen und das Ergebnis als Git-Repository benutzen -- der einzige Beweis,
        // der zaehlt.
        Files.createDirectories(RESTORE);
        assertThat(restic("restore", "latest", "--target", RESTORE.toString())).isZero();

        Path mirror = findDirectory(RESTORE, "werkzeug.git");
        assertThat(mirror).isNotNull();

        Path clone = WORKSPACE.resolve("klon-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(clone);
        git(clone, "clone", mirror.toString(), ".");

        assertThat(Files.readString(clone.resolve("README.md"))).contains("Größer, öfter, über");

        // Ein Mirror nimmt alle Zweige und Tags mit; ein Checkout haette nur einen Stand.
        Process branches = new ProcessBuilder("git", "branch", "--all")
                .directory(clone.toFile()).redirectErrorStream(true).start();
        String branchList = new String(branches.getInputStream().readAllBytes());
        branches.waitFor();

        assertThat(branchList).contains("nebenzweig");
    }

    @Test
    @DisplayName("Issues und Releases liegen als Datei daneben")
    void storesMetadata() throws Exception {
        // Sie liegen nicht im Git-Repository. Der Code liesse sich aus jedem Klon
        // wiederherstellen, die Diskussion darueber nicht.
        UUID planId = createPlan(true);
        runPlan(planId);

        Path restoreTarget = WORKSPACE.resolve("metadaten-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(restoreTarget);
        assertThat(restic("restore", "latest", "--target", restoreTarget.toString())).isZero();

        Path metadata = findFile(restoreTarget, "werkzeug.metadata.json");
        assertThat(metadata).isNotNull();
        assertThat(Files.readString(metadata))
                .contains("Etwas ist kaputt")
                .contains("Erste Fassung");
    }

    @Test
    @DisplayName("Der Token steht weder im Protokoll noch im gesicherten Klon")
    void tokenNeverLeaks() throws Exception {
        // Ein Token in der Adresse landet in der Prozessliste, in der Fehlermeldung und am
        // Ende in .git/config -- also in der Sicherung selbst.
        UUID planId = createPlan(false);
        BackupRun run = runPlan(planId);

        assertThat(runService.readLog(run.getId())).doesNotContain(TOKEN);

        Path restoreTarget = WORKSPACE.resolve("token-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(restoreTarget);
        assertThat(restic("restore", "latest", "--target", restoreTarget.toString())).isZero();

        try (var walk = Files.walk(restoreTarget)) {
            assertThat(walk.filter(Files::isRegularFile).filter(path -> {
                try {
                    return Files.readString(path).contains(TOKEN);
                } catch (IOException | RuntimeException e) {
                    return false;
                }
            })).isEmpty();
        }
    }

    private static Path findDirectory(Path root, String name) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst().orElse(null);
        }
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
