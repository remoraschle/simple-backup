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
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.secret.CredentialType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Ein vollständiger Lauf durch den echten Dienst, mit Datenbank und Spring-Kontext.
 *
 * <p>Dieser Test existiert wegen eines Fehlers, den keiner der Einzeltests fand: Die
 * Schritte wurden aus einer inneren Klasse heraus gespeichert, und Spring legt
 * {@code @Transactional} als Stellvertreter um die Bean -- ein Aufruf innerhalb derselben
 * Klasse geht daran vorbei. Der Lauf scheiterte daraufhin beim ersten Schritt.
 *
 * <p>Geprüft wird deshalb genau das, was damals fehlschlug: dass am Ende Schritte in der
 * Datenbank stehen.
 */
@Import(RunServiceIntegrationTest.TestBeans.class)
class RunServiceIntegrationTest extends IntegrationTestBase {

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount("/srv/daten", "/sources/daten", true, false),
                    new VolumeMount("/mnt/nas", "/mnt/nas", false, false)));
        }

        /** Statt echter Prozesse: Der Test prüft die Persistenz, nicht restic. */
        @Bean
        @Primary
        BackupExecutor testExecutor() {
            return new FakeBackupExecutor().defaultExitCode(0);
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private CredentialService credentials;

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID createPlan() {
        var password = credentials.create(unique("passwort"), CredentialType.RESTIC_REPOSITORY_PASSWORD,
                null, "geheim");

        var source = catalog.createSource(new CatalogRequests.SaveSource(unique("quelle"), null,
                new SourceConfig.LocalPath(List.of("/sources/daten"), List.of(), false)));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget(unique("ziel"), null,
                TargetMode.RESTIC, new TargetConfig.LocalPath("/mnt/nas/backup", password.id()), true));

        return catalog.createPlan(new CatalogRequests.SavePlan(unique("plan"), null, source.id(),
                List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 30, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, null)).id();
    }

    @Test
    @DisplayName("Ein Lauf schreibt seine Schritte in die Datenbank")
    void persistsStepsOfACompletedRun() {
        UUID planId = createPlan();

        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        BackupRun run = runService.findRun(runId);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        // Der eigentliche Punkt: Ohne Transaktion gäbe es hier keine Schritte, und der Lauf
        // wäre mit einem LazyInitializationException-Fehler gescheitert.
        assertThat(run.getSteps()).isNotEmpty();
        assertThat(run.getSteps()).extracting(RunStep::getKind)
                .containsExactly(StepKind.PREPARE, StepKind.TRANSFER);
        assertThat(run.getSteps()).allSatisfy(step ->
                assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCESS));
    }

    @Test
    @DisplayName("Der Plan merkt sich das Ergebnis des Laufs")
    void recordsResultOnThePlan() {
        UUID planId = createPlan();
        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        var plan = catalog.getPlan(planId);
        assertThat(plan.lastRunStatus()).isEqualTo("SUCCESS");
        assertThat(plan.lastRunAt()).isNotNull();
    }

    @Test
    @DisplayName("Für denselben Plan läuft nie ein zweiter Lauf gleichzeitig")
    void refusesConcurrentRunsOfTheSamePlan() {
        // Zwei Läufe würden sich am selben Repository gegenseitig aussperren; restic lässt
        // nur einen Schreiber zu.
        UUID planId = createPlan();

        UUID first = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();
        var second = runService.startRun(planId, RunTrigger.SCHEDULE);

        assertThat(second).isEmpty();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> runService.findRun(first).getStatus().isFinished());
    }

    @Test
    @DisplayName("Das Protokoll eines Laufs ist abrufbar")
    void writesAReadableLog() {
        UUID planId = createPlan();
        UUID runId = runService.startRun(planId, RunTrigger.MANUAL).orElseThrow();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> runService.findRun(runId).getStatus().isFinished());

        // In eine Datei und nicht in die Datenbank: Ein Lauf über viele Dateien erzeugt
        // Zehntausende Zeilen.
        assertThat(runService.readLog(runId)).contains("Repository prüfen").contains("$ restic");
    }
}
