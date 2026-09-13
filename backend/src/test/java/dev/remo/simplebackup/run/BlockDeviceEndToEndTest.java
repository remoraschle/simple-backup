package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.engine.DeviceAccess;
import dev.remo.simplebackup.engine.LocalProcessExecutor;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Ein Abbild eines echten Blockgeraets.
 *
 * <p>Geprueft wird an einem Loop-Geraet, also einem echten Eintrag unter {@code /dev} mit
 * einer Datei dahinter. Das ist naeher am Ernstfall als jede Nachbildung: derselbe Weg durch
 * {@code dd}, dieselbe Geraetedatei, dieselbe Freigabepruefung.
 *
 * <p>Die entscheidende Zusicherung ist die Gleichheit Byte fuer Byte. Ein Abbild, das sich
 * vom Datentraeger unterscheidet, ist im Ernstfall kein Abbild, sondern ein Schaden -- und
 * das merkt man erst, wenn man es zurueckspielt.
 *
 * <p>Laeuft nur mit Wurzelrechten und vorhandenem {@code losetup}; sonst wird er
 * uebersprungen statt zu scheitern.
 */
@EnabledIf("loopDevicesAvailable")
class BlockDeviceEndToEndTest {

    static boolean loopDevicesAvailable() {
        try {
            return "0".equals(run("id", "-u")) && new ProcessBuilder("losetup", "--version")
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Vier Megabyte: genug fuer mehrere Bloecke, klein genug fuer einen schnellen Test. */
    private static final int DISK_SIZE_BYTES = 4 * 1024 * 1024;

    @TempDir
    Path workspace;

    private Path backingFile;
    private Path staging;
    private String device;
    private BlockDeviceProducer producer;
    private RecordingProgressListener listener;

    @BeforeEach
    void setUp() throws Exception {
        backingFile = workspace.resolve("platte.raw");
        staging = Files.createDirectories(workspace.resolve("staging"));

        // Zufallsinhalt: Ein Abbild aus lauter Nullen saehe auch dann richtig aus, wenn
        // gar nichts gelesen wurde.
        byte[] content = new byte[DISK_SIZE_BYTES];
        new java.util.Random(42).nextBytes(content);
        Files.write(backingFile, content);

        device = run("losetup", "-f", "--show", backingFile.toString());
        assertThat(device).startsWith("/dev/loop");

        producer = producerFor(new DeviceAccess(true, null, List.of(device)));
        listener = new RecordingProgressListener();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (device != null) {
            run("losetup", "-d", device);
        }
    }

    private BlockDeviceProducer producerFor(DeviceAccess access) {
        var mounts = new MountTranslator(List.of(
                new VolumeMount(workspace.toString(), workspace.toString(), false, false)));

        var targets = new ResticTargets(Mockito.mock(CredentialService.class), mounts,
                new ObjectMapper());

        return new BlockDeviceProducer(new LocalProcessExecutor(new SecretRedactor()), targets,
                access, new SecretRedactor(),
                TestRunProperties.defaults().acquireTimeout(Duration.ofMinutes(2)).build());
    }

    private ExecutablePlan plan(SourceConfig source) {
        return new ExecutablePlan(UUID.randomUUID(), "Plattenabbild", "plan-test", "tag-test",
                source, List.of(), Duration.ofMinutes(5), null, NotifyOn.FAILURE);
    }

    private SourceConfig.BlockDevice source() {
        return new SourceConfig.BlockDevice(device, "platte.img", false);
    }

    @Test
    @DisplayName("Das Abbild ist Byte fuer Byte die Platte")
    void imageMatchesTheDeviceExactly() throws IOException {
        var prepared = producer.prepare(plan(source()), staging.toString(), listener);

        Path image = staging.resolve("platte.img");
        assertThat(image)
                .withFailMessage("Kein Abbild entstanden:%n%s", String.join("\n", listener.logLines))
                .exists();

        assertThat(Files.size(image)).isEqualTo(DISK_SIZE_BYTES);
        assertThat(Files.readAllBytes(image)).isEqualTo(Files.readAllBytes(backingFile));

        // Das Zwischenverzeichnis wandert in die Sicherung und wird danach aufgeraeumt.
        assertThat(prepared.paths()).containsExactly(staging.toString());
        assertThat(prepared.stagingDirectory()).isEqualTo(staging.toString());
    }

    @Test
    @DisplayName("Der Schritt erscheint in der Historie")
    void reportsItsOwnStep() {
        producer.prepare(plan(source()), staging.toString(), listener);

        assertThat(listener.descriptions()).containsExactly("Abbild von " + device);
    }

    @Test
    @DisplayName("Ein nicht freigegebenes Geraet wird nicht gelesen")
    void refusesDeviceThatIsNotAllowed() throws IOException {
        // Der Kern der Absicherung: Nicht der Inhalt eines Formularfelds entscheidet
        // darueber, welche Platte roh gelesen wird, sondern die Konfiguration.
        var restricted = producerFor(new DeviceAccess(true, null, List.of("/dev/loop99")));

        assertThatThrownBy(() -> restricted.prepare(plan(source()), staging.toString(), listener))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht freigegeben");

        assertThat(Files.list(staging)).isEmpty();
    }

    @Test
    @DisplayName("Ohne Wurzelrechte des Daemons wird gar nicht erst gelesen")
    void refusesWhenTheDaemonIsRootless() {
        var rootless = producerFor(new DeviceAccess(false, "Der Docker-Daemon läuft rootless.",
                List.of(device)));

        assertThatThrownBy(() -> rootless.prepare(plan(source()), staging.toString(), listener))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rootless");
    }

    @Test
    @DisplayName("Das Abbild wird nur lesend geholt -- die Platte bleibt, wie sie war")
    void leavesTheDeviceUntouched() throws IOException {
        byte[] before = Files.readAllBytes(backingFile);

        producer.prepare(plan(source()), staging.toString(), listener);

        assertThat(Files.readAllBytes(backingFile)).isEqualTo(before);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();
        return output;
    }
}
