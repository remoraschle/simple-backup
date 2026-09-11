package dev.remo.simplebackup.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VolumeMountTest {

    @Test
    @DisplayName("Quellen werden schreibgeschuetzt eingehaengt")
    void sourcesAreReadOnly() {
        // Das Werkzeug hat auf Originaldaten nichts zu schreiben. Ein schreibgeschuetzter
        // Mount macht einen Fehler unmoeglich statt nur unwahrscheinlich.
        var mount = VolumeMount.readOnlyPath("/srv/fotos", "/quellen/fotos");

        assertThat(mount.readOnly()).isTrue();
        assertThat(mount.toBindSpec()).isEqualTo("/srv/fotos:/quellen/fotos:ro");
    }

    @Test
    @DisplayName("Beschreibbare Einhaengungen tragen kein ro")
    void writableMountsOmitReadOnlyFlag() {
        assertThat(VolumeMount.writablePath("/mnt/nas", "/ziel").toBindSpec())
                .isEqualTo("/mnt/nas:/ziel");
    }

    @Test
    @DisplayName("Benannte Volumes brauchen keinen absoluten Pfad")
    void namedVolumesNeedNoPath() {
        assertThat(VolumeMount.volume("staging", "/var/lib/simple-backup/staging").toBindSpec())
                .isEqualTo("staging:/var/lib/simple-backup/staging");
    }

    @Test
    @DisplayName("Relative Pfade werden abgelehnt")
    void rejectsRelativePaths() {
        // Ein relativer Pfad wuerde vom Docker-Daemon als Volume-Name gedeutet -- das Backup
        // liefe dann ins Leere statt zu scheitern.
        assertThatThrownBy(() -> VolumeMount.readOnlyPath("srv/fotos", "/quelle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absoluter Host-Pfad");

        assertThatThrownBy(() -> VolumeMount.readOnlyPath("/srv/fotos", "quelle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolut");
    }

    @Test
    @DisplayName("Leere Angaben werden abgelehnt")
    void rejectsBlankValues() {
        assertThatThrownBy(() -> VolumeMount.readOnlyPath("", "/quelle"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
