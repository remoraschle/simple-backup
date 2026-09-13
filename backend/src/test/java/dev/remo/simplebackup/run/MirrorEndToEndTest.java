package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import dev.remo.simplebackup.shared.ConflictException;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
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

/**
 * Der Spiegel-Modus: eine direkt lesbare Kopie, ohne restic und ohne Passwort.
 *
 * <p>Genau das ist sein Zweck -- und sein Preis: Es gibt nur den letzten Stand. Beides wird
 * hier mit echtem rsync geprueft, denn beides muss man wissen, bevor man sich darauf verlaesst.
 */
@EnabledIf("rsyncAvailable")
@Import(MirrorEndToEndTest.TestBeans.class)
class MirrorEndToEndTest extends IntegrationTestBase {

    static boolean rsyncAvailable() {
        try {
            return new ProcessBuilder("rsync", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static final Path WORKSPACE = createWorkspace();
    private static final Path SOURCE = WORKSPACE.resolve("quelle");
    private static final Path MIRROR = WORKSPACE.resolve("spiegel");

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

    @Autowired
    private RunService runService;

    @Autowired
    private CatalogService catalog;

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("simple-backup-mirror-test");
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    @BeforeEach
    void prepareSource() throws IOException {
        Files.createDirectories(SOURCE.resolve("unterordner"));
        Files.createDirectories(MIRROR);

        Files.writeString(SOURCE.resolve("wichtig.txt"), "Meine wichtigen Daten\n", StandardCharsets.UTF_8);
        Files.writeString(SOURCE.resolve("unterordner/notizen.md"), "# Größer, öfter, über\n",
                StandardCharsets.UTF_8);
        Files.writeString(SOURCE.resolve("verwerfen.tmp"), "egal\n", StandardCharsets.UTF_8);
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

    private UUID mirrorTarget(String unique) {
        return catalog.createTarget(new CatalogRequests.SaveTarget("spiegel-" + unique, null,
                TargetMode.MIRROR, new TargetConfig.LocalPath(MIRROR.toString(), null), true)).id();
    }

    private UUID createPlan(String unique, UUID targetId) {
        var source = catalog.createSource(new CatalogRequests.SaveSource("quelle-" + unique, null,
                new SourceConfig.LocalPath(List.of(SOURCE.toString()), List.of("*.tmp"), false)));

        return catalog.createPlan(new CatalogRequests.SavePlan("plan-" + unique, null, source.id(),
                List.of(targetId), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.NEVER, null)).id();
    }

    private void run(UUID planId) {
        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        await().atMost(Duration.ofMinutes(2))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        assertThat(runService.findRun(runId).getStatus())
                .withFailMessage("Der Lauf ist gescheitert:%n%s", runService.readLog(runId))
                .isEqualTo(RunStatus.SUCCESS);
    }

    @Test
    @DisplayName("Der Spiegel ist eine direkt lesbare Kopie, ohne Werkzeug und ohne Passwort")
    void mirrorsFilesReadably() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        run(createPlan(unique, mirrorTarget(unique)));

        Path copy = MIRROR.resolve(SOURCE.getFileName());

        assertThat(Files.readString(copy.resolve("wichtig.txt"))).isEqualTo("Meine wichtigen Daten\n");
        assertThat(Files.readString(copy.resolve("unterordner/notizen.md")))
                .contains("Größer, öfter, über");

        // Ausschluesse gelten auch hier.
        assertThat(Files.exists(copy.resolve("verwerfen.tmp"))).isFalse();
    }

    @Test
    @DisplayName("Was an der Quelle geloescht wird, verschwindet beim naechsten Lauf auch im Spiegel")
    void deletionsPropagate() throws Exception {
        // Der Preis eines Spiegels, und er muss benannt sein: Wer Snapshots braucht, nimmt
        // den restic-Modus.
        String unique = UUID.randomUUID().toString().substring(0, 8);
        UUID planId = createPlan(unique, mirrorTarget(unique));
        run(planId);

        Path copy = MIRROR.resolve(SOURCE.getFileName());
        assertThat(Files.exists(copy.resolve("wichtig.txt"))).isTrue();

        Files.delete(SOURCE.resolve("wichtig.txt"));
        run(planId);

        assertThat(Files.exists(copy.resolve("wichtig.txt"))).isFalse();
        assertThat(Files.exists(copy.resolve("unterordner/notizen.md"))).isTrue();
    }

    @Test
    @DisplayName("Ein Spiegel-Ziel gehoert genau einem Plan")
    void mirrorTargetBelongsToOnePlan() {
        // Zwei Plaene auf demselben Verzeichnis loeschten sich gegenseitig die Daten weg --
        // abwechselnd, jede Nacht, ohne dass ein einziger Lauf fehlschluege.
        String unique = UUID.randomUUID().toString().substring(0, 8);
        UUID targetId = mirrorTarget(unique);
        createPlan(unique, targetId);

        String second = UUID.randomUUID().toString().substring(0, 8);

        assertThatThrownBy(() -> createPlan(second, targetId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("gehoert bereits zu einem anderen Plan");
    }

    @Test
    @DisplayName("Ein Spiegel braucht kein Repository-Passwort")
    void mirrorNeedsNoPassword() {
        // Es gibt nichts zu verschluesseln -- das ist der Sinn der Sache.
        String unique = UUID.randomUUID().toString().substring(0, 8);

        assertThat(catalog.getTarget(mirrorTarget(unique)).config().repositoryPasswordCredentialId())
                .isNull();
    }
}
