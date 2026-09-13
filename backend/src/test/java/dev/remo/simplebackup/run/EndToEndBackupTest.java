package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.catalog.ExecutableTarget;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.catalog.TargetMode;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.LocalProcessExecutor;
import dev.remo.simplebackup.engine.LogSink;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.restic.ResticOutputParser;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.RetentionRule;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Ein echtes Backup mit echtem restic, vom Plan bis zur Wiederherstellung.
 *
 * <p>Alle anderen Tests pruefen Teile. Dieser prueft, dass die Kette traegt: Pfade werden
 * uebersetzt, das Repository entsteht, das Passwort kommt als Datei an, restic laeuft, und --
 * das Entscheidende -- die gesicherten Dateien lassen sich unveraendert zurueckholen.
 *
 * <p>Ein Backup, das man nicht wiederhergestellt hat, ist eine Vermutung. Dasselbe gilt fuer
 * den Test dieses Backups.
 *
 * <p>Laeuft nur, wenn restic vorhanden ist -- auf einem Entwicklungsrechner ohne restic wird
 * er uebersprungen statt zu scheitern.
 */
@EnabledIf("resticAvailable")
class EndToEndBackupTest {

    static boolean resticAvailable() {
        try {
            return new ProcessBuilder("restic", "version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static final UUID PASSWORD_ID = UUID.randomUUID();
    private static final String REPOSITORY_PASSWORD = "ein-sehr-geheimes-repository-passwort";

    @TempDir
    Path workspace;

    private Path sourceDirectory;
    private Path repositoryDirectory;
    private LocalProcessExecutor executor;
    private BackupRunner runner;
    private RecordingProgressListener listener;

    @BeforeEach
    void setUp() throws IOException {
        sourceDirectory = Files.createDirectories(workspace.resolve("quelle"));
        repositoryDirectory = Files.createDirectories(workspace.resolve("repository"));

        Files.writeString(sourceDirectory.resolve("wichtig.txt"),
                "Diese Zeile muss die Wiederherstellung unveraendert ueberstehen.\n", StandardCharsets.UTF_8);
        Files.createDirectories(sourceDirectory.resolve("unterordner"));
        Files.writeString(sourceDirectory.resolve("unterordner/notizen.md"),
                "# Notizen\nMit Umlauten: größer, öfter, über.\n", StandardCharsets.UTF_8);
        Files.writeString(sourceDirectory.resolve("verwerfen.tmp"), "Sollte ausgeschlossen sein\n");

        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(PASSWORD_ID)).thenReturn(REPOSITORY_PASSWORD);

        // Ohne Container ist die Uebersetzung die Identitaet: Die Pfade existieren so, wie
        // sie sind. Geprueft wird hier die Kette, nicht die Uebersetzung -- die hat ihre
        // eigenen Tests.
        var mounts = new MountTranslator(List.of(
                new VolumeMount(workspace.toString(), workspace.toString(), false, false)));

        executor = new LocalProcessExecutor(new SecretRedactor());
        listener = new RecordingProgressListener();
        runner = new BackupRunner(executor, mounts, credentials,
                new ResticOutputParser(new ObjectMapper()), new SecretRedactor(), new ObjectMapper(),
                testProperties());
    }

    /** Kurze Zeitlimits: Ein Test soll nicht stundenlang auf ein Aufraeumen warten. */
    private static RunProperties testProperties() {
        return new RunProperties(null, null, 2, null, null, Duration.ofMinutes(2), null, null);
    }

    private ExecutablePlan plan() {
        return plan("plan-test", "tag-test", null);
    }

    private ExecutablePlan plan(String host, String tag, RetentionRule retention) {
        var target = new ExecutableTarget(UUID.randomUUID(), "Lokales Repository", TargetMode.RESTIC,
                new TargetConfig.LocalPath(repositoryDirectory.toString(), PASSWORD_ID), true);

        return new ExecutablePlan(UUID.randomUUID(), "Testplan", host, tag,
                new SourceConfig.LocalPath(List.of(sourceDirectory.toString()), List.of("*.tmp"), false),
                List.of(target), Duration.ofMinutes(5), retention, NotifyOn.FAILURE);
    }

    @Test
    @DisplayName("Ein Plan sichert wirklich, und die Dateien kommen unveraendert zurueck")
    void backsUpAndRestoresForReal() throws Exception {
        // ---------------------------------------------------------------- Sichern
        var outcomes = runner.run(plan(), listener);

        assertThat(outcomes).singleElement().satisfies(outcome ->
                assertThat(outcome.successful())
                        .withFailMessage("Sicherung fehlgeschlagen: %s%n%s", outcome.message(),
                                String.join("\n", listener.logLines))
                        .isTrue());

        // Beim ersten Lauf existiert das Repository noch nicht und wird angelegt.
        assertThat(listener.descriptions())
                .containsExactly("Repository prüfen", "Repository anlegen",
                        "Sicherung auf Lokales Repository");

        // restic hat eine Abschlussmeldung geliefert, aus der die Kennzahlen stammen.
        var summary = outcomes.getFirst().summary();
        assertThat(summary).isNotNull();
        assertThat(summary.snapshotId()).isNotBlank();
        assertThat(summary.filesNew()).isEqualTo(2);

        // -------------------------------------------- Wiederherstellen und vergleichen
        Path restoreTarget = Files.createDirectories(workspace.resolve("wiederhergestellt"));
        int exitCode = runRestic(ResticCommands.restore(summary.snapshotId(), restoreTarget.toString(),
                List.of()));

        assertThat(exitCode).isZero();

        Path restoredSource = restoreTarget.resolve(sourceDirectory.toString().substring(1));
        assertThat(Files.readString(restoredSource.resolve("wichtig.txt")))
                .isEqualTo(Files.readString(sourceDirectory.resolve("wichtig.txt")));

        // Umlaute ueberstehen den Weg durch Container, JSON und Dateisystem.
        assertThat(Files.readString(restoredSource.resolve("unterordner/notizen.md")))
                .contains("größer, öfter, über");

        // Das Ausschlussmuster hat gegriffen.
        assertThat(Files.exists(restoredSource.resolve("verwerfen.tmp"))).isFalse();
    }

    @Test
    @DisplayName("Das Repository ueberlebt einen zweiten Lauf und wird nicht neu angelegt")
    void secondRunReusesRepository() {
        runner.run(plan(), listener);

        var second = new RecordingProgressListener();
        var outcomes = runner.run(plan(), second);

        assertThat(outcomes.getFirst().successful()).isTrue();
        // Kein "Repository anlegen" mehr: Ein zweites Anlegen wuerde das vorhandene
        // Repository unbrauchbar machen.
        assertThat(second.descriptions())
                .containsExactly("Repository prüfen", "Sicherung auf Lokales Repository");

        // Unveraenderte Dateien werden erkannt und nicht erneut uebertragen.
        assertThat(outcomes.getFirst().summary().filesUnmodified()).isEqualTo(2);
    }

    @Test
    @DisplayName("restic prueft das erzeugte Repository als unversehrt")
    void repositoryPassesIntegrityCheck() throws Exception {
        runner.run(plan(), listener);

        // Der Beweis, dass nicht nur irgendetwas geschrieben wurde.
        assertThat(runRestic(ResticCommands.check(100))).isZero();
    }

    @Test
    @DisplayName("Die Aufbewahrungsregel loescht wirklich alte Snapshots")
    void retentionReallyDeletesOldSnapshots() throws Exception {
        var keepOne = plan("plan-test", "tag-test", new RetentionRule(1, null, null, null, null, null, null));

        runner.run(keepOne, listener);
        var second = new RecordingProgressListener();
        runner.run(keepOne, second);

        assertThat(second.descriptions()).contains("Alte Sicherungen aufräumen auf Lokales Repository");
        assertThat(snapshotCount("plan-test", "tag-test"))
                .withFailMessage("Es sollte genau ein Snapshot uebrig bleiben:%n%s",
                        String.join("\n", second.logLines))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Die Regel eines Plans laesst die Snapshots anderer Plaene in Ruhe")
    void retentionIsScopedToOnePlan() throws Exception {
        // Der gefaehrlichste Fehler dieser Funktion: Mehrere Plaene teilen sich oft ein
        // Repository. Ohne --host und --tag loeschte die Regel des einen die Sicherungen
        // aller anderen -- und zwar unbemerkt, denn sein eigener Lauf bliebe gruen.
        var withRetention = plan("plan-a", "tag-a", new RetentionRule(1, null, null, null, null, null, null));
        var withoutRetention = plan("plan-b", "tag-b", null);

        runner.run(withoutRetention, new RecordingProgressListener());
        runner.run(withoutRetention, new RecordingProgressListener());
        assertThat(snapshotCount("plan-b", "tag-b")).isEqualTo(2);

        runner.run(withRetention, new RecordingProgressListener());
        runner.run(withRetention, new RecordingProgressListener());

        assertThat(snapshotCount("plan-a", "tag-a")).isEqualTo(1);
        assertThat(snapshotCount("plan-b", "tag-b")).isEqualTo(2);
    }

    @Test
    @DisplayName("Das Repository-Passwort steht nicht im Protokoll")
    void passwordNeverAppearsInTheLog() {
        runner.run(plan(), listener);

        assertThat(String.join("\n", listener.logLines)).doesNotContain(REPOSITORY_PASSWORD);
    }

    /** Zaehlt die Snapshots eines Plans, indem restic selbst gefragt wird. */
    private int snapshotCount(String host, String tag) throws Exception {
        var output = new StringBuilder();
        runRestic(ResticCommands.snapshots(host, tag), output::append);

        return new ObjectMapper().readTree(output.toString()).size();
    }

    /** Fuehrt ein restic-Kommando gegen dasselbe Repository aus, fuer die Gegenproben. */
    private int runRestic(List<String> command) throws Exception {
        return runRestic(command, LogSink.discarding());
    }

    private int runRestic(List<String> command, LogSink sink) throws Exception {
        var request = ExecutionRequest.builder("egal", command.toArray(String[]::new))
                .env("RESTIC_REPOSITORY", repositoryDirectory.toString())
                .env("RESTIC_PASSWORD", REPOSITORY_PASSWORD)
                .timeout(Duration.ofMinutes(5))
                .build();

        return executor.start(request, sink)
                .awaitCompletion(Duration.ofMinutes(5))
                .exitCode();
    }
}
