package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DockerRuntimeTest {

    @Test
    @DisplayName("Rootless wird an den Sicherheitsoptionen erkannt")
    void detectsRootless() {
        var runtime = DockerRuntime.from(new DockerDto.SystemInfo(
                List.of("name=seccomp,profile=builtin", "name=rootless", "name=cgroupns"),
                "29.3.1", "Ubuntu 24.04"));

        assertThat(runtime.rootless()).isTrue();
        assertThat(runtime.serverVersion()).isEqualTo("29.3.1");
    }

    @Test
    @DisplayName("Ein Daemon mit Wurzelrechten wird als solcher erkannt")
    void detectsRootfulDocker() {
        var runtime = DockerRuntime.from(new DockerDto.SystemInfo(
                List.of("name=seccomp,profile=builtin", "name=apparmor"), "29.3.1", "Ubuntu 24.04"));

        assertThat(runtime.rootless()).isFalse();
    }

    @Test
    @DisplayName("Fehlen die Sicherheitsoptionen, gilt der Daemon nicht als rootless")
    void handlesMissingSecurityOptions() {
        // Aeltere Daemons liefern das Feld nicht. Rootful ist die sichere Annahme: Sie
        // schraenkt keine Funktion ein, die spaeter doch scheitern wuerde.
        assertThat(DockerRuntime.from(new DockerDto.SystemInfo(null, "24.0.0", "Debian")).rootless())
                .isFalse();
    }

    @Test
    @DisplayName("Blockgeraete stehen nur mit Wurzelrechten zur Verfuegung")
    void blockDevicesRequireRootfulDaemon() {
        var rootless = new DockerRuntime(true, "29.3.1");
        var rootful = new DockerRuntime(false, "29.3.1");

        assertThat(rootless.supportsBlockDevices()).isFalse();
        assertThat(rootful.supportsBlockDevices()).isTrue();
    }

    @Test
    @DisplayName("Die Begruendung nennt den Grund und den Ausweg")
    void explainsWhyBlockDevicesAreUnavailable() {
        // Steht in der Oberflaeche, damit niemand raetselt, warum der Quelltyp fehlt.
        assertThat(new DockerRuntime(true, "29.3.1").blockDeviceUnavailableReason())
                .contains("rootless")
                .contains("Wurzelrechten");

        assertThat(new DockerRuntime(false, "29.3.1").blockDeviceUnavailableReason()).isNull();
    }
}
