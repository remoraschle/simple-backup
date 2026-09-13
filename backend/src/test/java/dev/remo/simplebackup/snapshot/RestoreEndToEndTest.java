package dev.remo.simplebackup.snapshot;

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
import dev.remo.simplebackup.run.RunService;
import dev.remo.simplebackup.run.RunTrigger;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.secret.CredentialType;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
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
 * Der Ernstfall, einmal ganz durchgespielt: sichern, blaettern, zurueckholen, pruefen.
 *
 * <p>Mit echtem restic und echter Datenbank, denn genau darum geht es. Ein Backup, das man
 * nie wiederhergestellt hat, ist eine Vermutung -- und ein Wiederherstellungscode, den nur
 * Mocks gesehen haben, erst recht.
 */
@EnabledIf("resticAvailable")
@Import(RestoreEndToEndTest.TestBeans.class)
class RestoreEndToEndTest extends IntegrationTestBase {

    static boolean resticAvailable() {
        try {
            return new ProcessBuilder("restic", "version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Ein festes Arbeitsverzeichnis statt {@code @TempDir}: Die Einhaengungstabelle ist eine
     * Bean und entsteht beim Hochfahren des Kontexts, also bevor JUnit ein Verzeichnis je
     * Test anlegen wuerde.
     */
    private static final Path WORKSPACE = createWorkspace();

    private static final Path SOURCE = WORKSPACE.resolve("quelle");
    private static final Path REPOSITORY = WORKSPACE.resolve("repo");
    private static final Path RESTORE = WORKSPACE.resolve("wiederhergestellt");
    private static final Path STAGING = WORKSPACE.resolve("staging");

    private static final String PASSWORD = "ein-sehr-geheimes-repository-passwort";

    @TestConfiguration
    static class TestBeans {

        /** Der Runner sieht dieselben Pfade wie das Backend -- hier laeuft beides lokal. */
        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount(WORKSPACE.toString(), WORKSPACE.toString(), false, false)));
        }

        /** Echte Prozesse: Dieser Test prueft restic, nicht die Verdrahtung. */
        @Bean
        @Primary
        BackupExecutor testExecutor() {
            return new LocalProcessExecutor(new SecretRedactor());
        }
    }

    @DynamicPropertySource
    static void stagingDirectory(DynamicPropertyRegistry registry) {
        registry.add("simplebackup.snapshot.staging-directory", STAGING::toString);
    }

    @Autowired
    private RunService runService;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private CredentialService credentials;

    @Autowired
    private SnapshotService snapshots;

    @Autowired
    private SnapshotBrowser browser;

    @Autowired
    private RestoreService restores;

    @Autowired
    private IntegrityService integrity;

    private UUID planId;
    private UUID targetId;
    private byte[] binaryContent;

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("simple-backup-restore-test");
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    @BeforeEach
    void prepare() throws IOException {
        Files.createDirectories(SOURCE.resolve("unterordner"));
        Files.createDirectories(REPOSITORY);
        Files.createDirectories(STAGING);

        Files.writeString(SOURCE.resolve("wichtig.txt"), "Meine wichtigen Daten\n", StandardCharsets.UTF_8);
        Files.writeString(SOURCE.resolve("unterordner/notizen.md"), "# Größer, öfter, über\n",
                StandardCharsets.UTF_8);

        // Bewusst binaer und mit Nullbytes: Ueber die Zeilenausgabe des Runners kaeme diese
        // Datei beschaedigt zurueck, und genau das faellt erst im Ernstfall auf.
        binaryContent = new byte[4096];
        new Random(42).nextBytes(binaryContent);
        Files.write(SOURCE.resolve("bild.bin"), binaryContent);

        createPlanAndRun();
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

    private void createPlanAndRun() {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        var password = credentials.create("passwort-" + unique,
                CredentialType.RESTIC_REPOSITORY_PASSWORD, null, PASSWORD);

        var source = catalog.createSource(new CatalogRequests.SaveSource("quelle-" + unique, null,
                new SourceConfig.LocalPath(List.of(SOURCE.toString()), List.of(), false)));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget("ziel-" + unique, null,
                TargetMode.RESTIC, new TargetConfig.LocalPath(REPOSITORY.toString(), password.id()), true));

        targetId = target.id();
        planId = catalog.createPlan(new CatalogRequests.SavePlan("plan-" + unique, null, source.id(),
                List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.NEVER, null)).id();

        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        await().atMost(Duration.ofMinutes(2))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        assertThat(runService.findRun(runId).getStatus().name())
                .withFailMessage("Die Sicherung ist gescheitert:%n%s", runService.readLog(runId))
                .isEqualTo("SUCCESS");
    }

    private SnapshotViews.SnapshotView newestSnapshot() {
        var found = snapshots.list(planId, null);
        assertThat(found).isNotEmpty();
        return found.getFirst();
    }

    @Test
    @DisplayName("Ein Lauf traegt seinen Snapshot ins Verzeichnis ein")
    void recordsTheSnapshotOfARun() {
        var snapshot = newestSnapshot();

        assertThat(snapshot.externalId()).isNotBlank();
        assertThat(snapshot.shortId()).hasSize(8);
        assertThat(snapshot.runId()).isNotNull();
        assertThat(snapshot.targetId()).isEqualTo(targetId);
    }

    @Test
    @DisplayName("Der Abgleich mit dem Repository bestaetigt, was im Verzeichnis steht")
    void refreshAgreesWithTheRepository() {
        // Das Verzeichnis ist nur eine Abschrift. Weicht es vom Repository ab, ist es
        // schlimmer als keines -- also wird es gegen die Wahrheit geprueft.
        var fromRepository = browser.refresh(targetId, planId);

        assertThat(fromRepository).hasSize(1);
        assertThat(fromRepository.getFirst().externalId()).isEqualTo(newestSnapshot().externalId());
    }

    @Test
    @DisplayName("Im Snapshot laesst sich blaettern, Ebene fuer Ebene")
    void browsesTheSnapshotLevelByLevel() {
        var snapshot = newestSnapshot();

        // Von der Wurzel bis zum Quellverzeichnis: restic legt den vollen Pfad ab.
        String path = "/";
        for (Path segment : SOURCE) {
            var level = browser.browse(snapshot.id(), path);
            assertThat(level.entries())
                    .withFailMessage("Unter %s gibt es keinen Eintrag %s", path, segment)
                    .anySatisfy(entry -> assertThat(entry.name()).isEqualTo(segment.toString()));

            path = path.equals("/") ? "/" + segment : path + "/" + segment;
        }

        var contents = browser.browse(snapshot.id(), SOURCE.toString());

        assertThat(contents.entries()).extracting(SnapshotViews.EntryView::name)
                .containsExactlyInAnyOrder("unterordner", "wichtig.txt", "bild.bin");

        // Nur die unmittelbaren Kinder: Ein Snapshot mit Hunderttausenden Dateien wuerde
        // sonst weder durchs Netz passen noch in eine Anzeige.
        assertThat(contents.entries()).noneSatisfy(entry ->
                assertThat(entry.path()).endsWith("notizen.md"));

        assertThat(contents.entries()).filteredOn(SnapshotViews.EntryView::directory)
                .singleElement()
                .satisfies(entry -> assertThat(entry.name()).isEqualTo("unterordner"));
    }

    @Test
    @DisplayName("Eine Wiederherstellung bringt die Dateien unveraendert zurueck")
    void restoresFilesUnchanged() throws IOException {
        Files.createDirectories(RESTORE);
        var snapshot = newestSnapshot();

        var job = restores.start(snapshot.id(), RESTORE.toString(), List.of());

        await().atMost(Duration.ofMinutes(2))
                .until(() -> job.getState() != RestoreJob.State.RUNNING);

        assertThat(job.getState())
                .withFailMessage("Wiederherstellung fehlgeschlagen: %s%n%s", job.getMessage(),
                        String.join("\n", job.getLog()))
                .isEqualTo(RestoreJob.State.SUCCEEDED);

        Path restored = RESTORE.resolve(SOURCE.toString().substring(1));

        assertThat(Files.readString(restored.resolve("wichtig.txt")))
                .isEqualTo(Files.readString(SOURCE.resolve("wichtig.txt")));
        assertThat(Files.readString(restored.resolve("unterordner/notizen.md")))
                .contains("Größer, öfter, über");
    }

    @Test
    @DisplayName("Eine einzelne Datei kommt Byte fuer Byte zurueck, auch binaer")
    void fetchesASingleFileByteForByte() throws IOException {
        // Der haeufigste Ernstfall ueberhaupt: Eine Datei ist weg, und man braucht genau sie.
        var snapshot = newestSnapshot();

        Path fetched = restores.fetchSingleFile(snapshot.id(), SOURCE.resolve("bild.bin").toString());

        try {
            assertThat(Files.readAllBytes(fetched)).isEqualTo(binaryContent);
        } finally {
            restores.discard(fetched);
        }
    }

    @Test
    @DisplayName("Das Arbeitsverzeichnis bleibt nach einem Download nicht liegen")
    void cleansUpAfterDownload() throws IOException {
        var snapshot = newestSnapshot();

        Path fetched = restores.fetchSingleFile(snapshot.id(), SOURCE.resolve("wichtig.txt").toString());
        restores.discard(fetched);

        try (var entries = Files.list(STAGING)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    @DisplayName("restic bestaetigt das Repository als unversehrt")
    void repositoryPassesCheck() {
        var outcome = integrity.check(targetId, 100);

        assertThat(outcome.successful())
                .withFailMessage("Pruefung fehlgeschlagen: %s", outcome.message())
                .isTrue();
    }

    @Test
    @DisplayName("Die Stichprobe holt eine echte Datei zurueck und vergleicht sie")
    void sampleRestoreComparesWithTheOriginal() {
        // Die einzige Probe, die die Frage beantwortet, auf die es ankommt: Kommt im
        // Ernstfall etwas Brauchbares heraus?
        var outcome = integrity.verifyByRestoringOneFile(targetId);

        assertThat(outcome.successful())
                .withFailMessage("Stichprobe fehlgeschlagen: %s", outcome.message())
                .isTrue();
        assertThat(outcome.path()).startsWith(SOURCE.toString());
    }
}
