package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.engine.VolumeMount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfInspectorTest {

    @Test
    @DisplayName("Ein Bind-Mount liefert den Host-Pfad")
    void mapsBindMountToHostPath() {
        // Der Docker-Daemon loest Bind-Mounts gegen das Host-Dateisystem auf; nur dieser
        // Pfad ist fuer einen Runner brauchbar.
        var mounts = SelfInspector.toVolumeMounts(List.of(
                new DockerDto.MountPoint("bind", null, "/srv/fotos", "/sources/fotos", false)));

        assertThat(mounts).singleElement().satisfies(mount -> {
            assertThat(mount.source()).isEqualTo("/srv/fotos");
            assertThat(mount.target()).isEqualTo("/sources/fotos");
            assertThat(mount.namedVolume()).isFalse();
            assertThat(mount.readOnly()).isTrue();
        });
    }

    @Test
    @DisplayName("Ein Volume liefert seinen Namen, nicht seinen Pfad auf dem Host")
    void mapsVolumeToName() {
        // Nur ueber den Namen laesst sich dasselbe Volume in einen anderen Container
        // einhaengen -- der Pfad unter /var/lib/docker waere dafuer unbrauchbar.
        var mounts = SelfInspector.toVolumeMounts(List.of(
                new DockerDto.MountPoint("volume", "staging",
                        "/var/lib/docker/volumes/staging/_data",
                        "/var/lib/simple-backup/staging", true)));

        assertThat(mounts).singleElement().satisfies(mount -> {
            assertThat(mount.source()).isEqualTo("staging");
            assertThat(mount.namedVolume()).isTrue();
            assertThat(mount.readOnly()).isFalse();
            assertThat(mount.toBindSpec()).isEqualTo("staging:/var/lib/simple-backup/staging");
        });
    }

    @Test
    @DisplayName("Schreibschutz wird aus dem RW-Kennzeichen abgeleitet")
    void derivesReadOnlyFromRwFlag() {
        var mounts = SelfInspector.toVolumeMounts(List.of(
                new DockerDto.MountPoint("bind", null, "/srv/a", "/a", true),
                new DockerDto.MountPoint("bind", null, "/srv/b", "/b", false)));

        assertThat(mounts.get(0).readOnly()).isFalse();
        assertThat(mounts.get(1).readOnly()).isTrue();
    }

    @Test
    @DisplayName("Eine fehlende Mount-Tabelle ergibt eine leere Liste")
    void handlesMissingMounts() {
        assertThat(SelfInspector.toVolumeMounts(null)).isEmpty();
    }

    @Test
    @DisplayName("Eine ausdrueckliche Kennung hat Vorrang vor dem Hostnamen")
    void configuredIdWins() {
        // Fuer den Fall, dass in der Compose-Datei ein eigener Hostname gesetzt wurde.
        assertThat(SelfInspector.detectContainerId("abc123", Map.of("HOSTNAME", "anderer")))
                .contains("abc123");
    }

    @Test
    @DisplayName("Ohne Konfiguration dient der Hostname als Kennung")
    void fallsBackToHostname() {
        // Docker setzt den Hostnamen eines Containers auf dessen gekuerzte Kennung.
        assertThat(SelfInspector.detectContainerId(null, Map.of("HOSTNAME", "c0ffee123456")))
                .contains("c0ffee123456");
    }

    @Test
    @DisplayName("Ausserhalb eines Containers gibt es keine Kennung")
    void returnsEmptyOutsideContainer() {
        assertThat(SelfInspector.detectContainerId(null, Map.of())).isEmpty();
        assertThat(SelfInspector.detectContainerId("  ", Map.of("HOSTNAME", ""))).isEmpty();
    }
}
