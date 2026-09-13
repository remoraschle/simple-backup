package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.catalog.ExecutableTarget;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.catalog.TargetMode;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticOutputParser;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.RetentionRule;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class BackupRunnerTest {

    private static final UUID PASSWORD_ID = UUID.randomUUID();
    private static final UUID S3_KEY_ID = UUID.randomUUID();

    private FakeBackupExecutor executor;
    private RecordingProgressListener listener;
    private BackupRunner runner;

    @BeforeEach
    void setUp() {
        executor = new FakeBackupExecutor();
        listener = new RecordingProgressListener();

        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(PASSWORD_ID)).thenReturn("repo-passwort");
        Mockito.when(credentials.reveal(S3_KEY_ID)).thenReturn("""
                {"accessKeyId":"AKIAIOSFODNN7EXAMPLE","secretAccessKey":"geheimer-schluessel"}""");

        var mounts = new MountTranslator(List.of(
                new VolumeMount("/srv/fotos", "/sources/fotos", true, false),
                new VolumeMount("/mnt/nas", "/mnt/nas", false, false)));

        var targets = new ResticTargets(credentials, mounts, new ObjectMapper());

        runner = new BackupRunner(executor, targets, new ResticOutputParser(new ObjectMapper()),
                new SecretRedactor(), new SourceProducers(List.of(new LocalPathProducer(targets))),
                testProperties());
    }

    /** Kurze Zeitlimits: Ein Test soll nicht stundenlang auf ein Aufraeumen warten. */
    private static RunProperties testProperties() {
        return new RunProperties(null, null, 2, null, null, Duration.ofMinutes(2), null, null,
                null, null, null, null);
    }

    private static ExecutableTarget localTarget(String name, String path) {
        return new ExecutableTarget(UUID.randomUUID(), name, TargetMode.RESTIC,
                new TargetConfig.LocalPath(path, PASSWORD_ID), true);
    }

    private static ExecutablePlan planWith(ExecutableTarget... targets) {
        return planWith(null, targets);
    }

    private static ExecutablePlan planWith(RetentionRule retention, ExecutableTarget... targets) {
        return new ExecutablePlan(UUID.randomUUID(), "Fotos", "plan-fotos", "tag-fotos",
                new SourceConfig.LocalPath(List.of("/sources/fotos"), List.of("*.tmp"), false),
                List.of(targets), Duration.ofMinutes(30), retention, NotifyOn.FAILURE);
    }

    @Nested
    @DisplayName("Aufbewahrung")
    class Retention {

        @Test
        @DisplayName("Ohne Aufbewahrungsregel wird nichts geloescht")
        void withoutRuleNothingIsDeleted() {
            executor.whenCommandContains("config", 0);

            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(executor.commands()).noneMatch(command -> command.contains("forget"));
        }

        @Test
        @DisplayName("Mit Regel wird nach der Sicherung aufgeraeumt, eingegrenzt auf den Plan")
        void appliesRuleScopedToThePlan() {
            // Ohne --host und --tag wuerde die Regel eines Plans die Snapshots aller anderen
            // Plaene im selben Repository mitloeschen.
            executor.whenCommandContains("config", 0);

            runner.run(planWith(new RetentionRule(null, null, 7, 4, null, null, null),
                    localTarget("NAS", "/mnt/nas/backups")), listener);

            String forget = executor.commands().stream()
                    .filter(command -> command.contains("forget"))
                    .findFirst()
                    .map(command -> String.join(" ", command))
                    .orElseThrow(() -> new AssertionError("Es wurde nicht aufgeraeumt"));

            assertThat(forget)
                    .contains("--host plan-fotos")
                    .contains("--tag tag-fotos")
                    .contains("--keep-daily 7")
                    .contains("--keep-weekly 4")
                    .contains("--prune");
        }

        @Test
        @DisplayName("Aufgeraeumt wird erst nach der Sicherung")
        void prunesAfterBackup() {
            // Andersherum loeschte die Regel alte Snapshots, ohne dass ein neuer dazugekommen
            // waere -- und ein gescheitertes Backup haette die Aufbewahrung schon verbraucht.
            executor.whenCommandContains("config", 0);

            runner.run(planWith(RetentionRule.sensibleDefault(), localTarget("NAS", "/mnt/nas/backups")),
                    listener);

            assertThat(listener.descriptions())
                    .containsExactly("Repository prüfen", "Sicherung auf NAS",
                            "Alte Sicherungen aufräumen auf NAS");
        }

        @Test
        @DisplayName("Eine gescheiterte Sicherung wird gar nicht erst aufgeraeumt")
        void doesNotPruneAfterFailedBackup() {
            executor.whenCommandContains("config", 0)
                    .whenCommandContains("backup", 1, "Fatal: unable to read source");

            runner.run(planWith(RetentionRule.sensibleDefault(), localTarget("NAS", "/mnt/nas/backups")),
                    listener);

            assertThat(executor.commands()).noneMatch(command -> command.contains("forget"));
        }

        @Test
        @DisplayName("Ein gescheitertes Aufraeumen macht die Sicherung nicht ungueltig")
        void failedPruneKeepsTheBackupValid() {
            // Die Daten sind geschrieben. Wer das Gegenteil meldet, treibt jemanden dazu,
            // ein gelungenes Backup noch einmal laufen zu lassen.
            executor.whenCommandContains("config", 0)
                    .whenCommandContains("forget", 1, "Fatal: repository is locked");

            var outcomes = runner.run(planWith(RetentionRule.sensibleDefault(),
                    localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(outcomes).singleElement().satisfies(outcome ->
                    assertThat(outcome.successful()).isTrue());
        }
    }

    @Nested
    @DisplayName("Ablauf")
    class Sequence {

        @Test
        @DisplayName("Bei vorhandenem Repository wird nur gesichert, nicht neu angelegt")
        void skipsInitWhenRepositoryExists() {
            // Ein zweites Anlegen wuerde ein vorhandenes Repository unbrauchbar machen.
            executor.whenCommandContains("config", 0);

            var outcomes = runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(outcomes).singleElement().satisfies(outcome ->
                    assertThat(outcome.successful()).isTrue());
            assertThat(listener.descriptions()).containsExactly("Repository prüfen", "Sicherung auf NAS");
            assertThat(executor.commands()).noneMatch(command -> command.contains("init"));
        }

        @Test
        @DisplayName("Fehlt das Repository, wird es angelegt und danach gesichert")
        void initialisesMissingRepository() {
            executor.whenCommandContains("config", 1, "Fatal: unable to open config file");

            var outcomes = runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(listener.descriptions())
                    .containsExactly("Repository prüfen", "Repository anlegen", "Sicherung auf NAS");
            assertThat(outcomes).singleElement().satisfies(outcome ->
                    assertThat(outcome.successful()).isTrue());
        }

        @Test
        @DisplayName("Ein fehlendes Repository gilt als uebersprungen, nicht als fehlgeschlagen")
        void missingRepositoryIsNotAFailure() {
            // Beim ersten Lauf eines Ziels ist das der Normalfall. "Fehlgeschlagen" in der
            // Historie liesse jedes neue Ziel nach einem Fehler aussehen, den es nie gab --
            // und wer die Historie nicht ernst nimmt, uebersieht den echten Fehler.
            executor.whenCommandContains("config", 1,
                    "Is there a repository at the following location?", "/mnt/nas/backups");

            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            var probe = listener.started.getFirst();
            assertThat(listener.finished.get(probe.id())).isEqualTo(StepStatus.SKIPPED);
            assertThat(listener.messages.get(probe.id())).isEqualTo("Noch nicht vorhanden — wird angelegt");
        }

        @Test
        @DisplayName("Laesst sich das Repository nicht anlegen, wird nicht gesichert")
        void abortsWhenInitialisationFails() {
            executor.whenCommandContains("config", 1)
                    .whenCommandContains("init", 1, "Fatal: unable to create repository");

            var outcomes = runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.successful()).isFalse();
                assertThat(outcome.message()).contains("Repository liess sich nicht anlegen");
            });
            assertThat(executor.commands()).noneMatch(command -> command.contains("backup"));
        }
    }

    @Nested
    @DisplayName("Mehrere Ziele")
    class MultipleTargets {

        @Test
        @DisplayName("Ein gescheitertes Ziel haelt die uebrigen nicht auf")
        void oneFailingTargetDoesNotStopTheOthers() {
            // Genau daraus entsteht der Teilerfolg: NAS erreichbar, das zweite Ziel nicht.
            //
            // Die Unterscheidung laeuft ueber das Repository und nicht ueber das Kommando:
            // Das Ziel steht in RESTIC_REPOSITORY, die Kommandos beider Ziele sind identisch.
            executor.whenRepositoryContains("/mnt/nas/kaputt", 1, "Fatal: repository not reachable")
                    .whenCommandContains("config", 0);

            var funktioniert = localTarget("NAS", "/mnt/nas/backups");
            var kaputt = new ExecutableTarget(UUID.randomUUID(), "Defekt", TargetMode.RESTIC,
                    new TargetConfig.LocalPath("/mnt/nas/kaputt", PASSWORD_ID), true);

            var outcomes = runner.run(planWith(funktioniert, kaputt), listener);

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.getFirst().successful()).isTrue();
            assertThat(outcomes.get(1).successful()).isFalse();
            assertThat(outcomes.get(1).targetName()).isEqualTo("Defekt");

            // Das funktionierende Ziel wurde trotzdem vollstaendig bedient.
            assertThat(listener.descriptions()).contains("Sicherung auf NAS");
        }

        @Test
        @DisplayName("Abgeschaltete Ziele werden uebergangen")
        void skipsDisabledTargets() {
            executor.whenCommandContains("config", 0);
            var aktiv = localTarget("Aktiv", "/mnt/nas/aktiv");
            var aus = new ExecutableTarget(UUID.randomUUID(), "Aus", TargetMode.RESTIC,
                    new TargetConfig.LocalPath("/mnt/nas/aus", PASSWORD_ID), false);

            var outcomes = runner.run(planWith(aktiv, aus), listener);

            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.getFirst().targetName()).isEqualTo("Aktiv");
        }

        @Test
        @DisplayName("Der Spiegel-Modus wird als noch nicht umgesetzt gemeldet")
        void reportsMirrorModeAsUnimplemented() {
            // Ehrlicher als ein stiller Erfolg, der nichts gesichert hat.
            var spiegel = new ExecutableTarget(UUID.randomUUID(), "USB", TargetMode.MIRROR,
                    new TargetConfig.LocalPath("/mnt/nas/spiegel", null), true);

            var outcomes = runner.run(planWith(spiegel), listener);

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.skipped()).isTrue();
                assertThat(outcome.successful()).isFalse();
                assertThat(outcome.message()).contains("noch nicht umgesetzt");
            });
        }
    }

    @Nested
    @DisplayName("Kommandos und Einhaengungen")
    class CommandsAndMounts {

        @Test
        @DisplayName("Die Quelle wird schreibgeschuetzt eingehaengt")
        void mountsSourceReadOnly() {
            // Das Werkzeug hat auf Originaldaten nichts zu schreiben.
            executor.whenCommandContains("config", 0);
            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            var backup = executor.requestContaining("backup");
            assertThat(backup.mounts())
                    .filteredOn(mount -> mount.target().equals("/sources/fotos"))
                    .singleElement()
                    .satisfies(mount -> {
                        assertThat(mount.readOnly()).isTrue();
                        // Host-Pfad, nicht der Pfad im Backend-Container.
                        assertThat(mount.source()).isEqualTo("/srv/fotos");
                    });
        }

        @Test
        @DisplayName("Das Ziel wird beschreibbar eingehaengt")
        void mountsTargetWritable() {
            executor.whenCommandContains("config", 0);
            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(executor.requestContaining("backup").mounts())
                    .filteredOn(mount -> mount.target().equals("/mnt/nas/backups"))
                    .singleElement()
                    .satisfies(mount -> assertThat(mount.readOnly()).isFalse());
        }

        @Test
        @DisplayName("Host und Kennzeichnung des Plans stehen im Kommando")
        void passesStableHostAndTag() {
            // Ohne sie waeren die Snapshots eines Plans nicht als zusammengehoerig
            // erkennbar, und forget wuerde fremde Snapshots mitloeschen.
            executor.whenCommandContains("config", 0);
            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(executor.requestContaining("backup").command())
                    .containsSubsequence("--host", "plan-fotos")
                    .containsSubsequence("--tag", "tag-fotos")
                    .containsSubsequence("--exclude", "*.tmp");
        }

        @Test
        @DisplayName("Das Repository-Passwort geht als Datei, nicht als Umgebungsvariable")
        void passesPasswordAsFile() {
            executor.whenCommandContains("config", 0);
            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            var request = executor.requestContaining("backup");
            assertThat(request.secretFiles()).containsEntry("restic-password", "repo-passwort");
            assertThat(request.environment())
                    .containsEntry("RESTIC_PASSWORD_FILE", "/run/secrets/restic-password")
                    .doesNotContainKey("RESTIC_PASSWORD");
            assertThat(request.environment().values()).noneMatch(value -> value.contains("repo-passwort"));
        }

        @Test
        @DisplayName("S3-Zugangsdaten landen in einer Datei im AWS-Format")
        void passesS3CredentialsAsFile() {
            executor.whenCommandContains("config", 0);
            var s3 = new ExecutableTarget(UUID.randomUUID(), "S3", TargetMode.RESTIC,
                    new TargetConfig.S3("https://s3.amazonaws.com", "backups", "fotos",
                            S3_KEY_ID, PASSWORD_ID), true);

            runner.run(planWith(s3), listener);

            var request = executor.requestContaining("backup");
            assertThat(request.environment())
                    .containsEntry("RESTIC_REPOSITORY", "s3:https://s3.amazonaws.com/backups/fotos")
                    .containsEntry("AWS_SHARED_CREDENTIALS_FILE", "/run/secrets/aws-credentials")
                    .doesNotContainKey("AWS_ACCESS_KEY_ID");
            assertThat(request.secretFiles().get("aws-credentials"))
                    .contains("aws_access_key_id = AKIAIOSFODNN7EXAMPLE");
        }
    }

    @Nested
    @DisplayName("Rueckmeldung")
    class Feedback {

        @Test
        @DisplayName("Fortschrittsmeldungen werden gemeldet")
        void reportsProgress() {
            executor.whenCommandContains("config", 0)
                    .whenCommandContains("backup", 0,
                            """
                            {"message_type":"status","percent_done":0.5,"files_done":50}""",
                            """
                            {"message_type":"summary","files_new":10,"data_added":1024,\
                            "snapshot_id":"abc123"}""");

            var outcomes = runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(listener.progressUpdates).singleElement()
                    .satisfies(progress -> assertThat(progress.percent()).isEqualTo(50));

            assertThat(outcomes.getFirst().summary()).isNotNull()
                    .satisfies(summary -> {
                        assertThat(summary.filesNew()).isEqualTo(10);
                        assertThat(summary.snapshotId()).isEqualTo("abc123");
                    });
        }

        @Test
        @DisplayName("Ausgabezeilen werden weitergereicht")
        void forwardsLogLines() {
            executor.whenCommandContains("config", 0)
                    .whenCommandContains("backup", 0, "scanning...", "done");

            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(listener.logLines).contains("scanning...", "done");
        }

        @Test
        @DisplayName("Jeder Schritt wird begonnen und beendet gemeldet")
        void reportsEveryStep() {
            executor.whenCommandContains("config", 0);
            runner.run(planWith(localTarget("NAS", "/mnt/nas/backups")), listener);

            assertThat(listener.started).hasSize(2);
            assertThat(listener.finished).hasSize(2);
            assertThat(listener.finished.values()).allMatch(status -> status == StepStatus.SUCCESS);
            assertThat(listener.stepKinds()).containsExactly(StepKind.PREPARE, StepKind.TRANSFER);
        }
    }
}
