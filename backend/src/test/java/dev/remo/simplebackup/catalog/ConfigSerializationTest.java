package dev.remo.simplebackup.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Konfiguration liegt als JSONB in der Datenbank. Geht die Umwandlung schief, ist eine
 * Quelle oder ein Ziel nach einem Neustart unbrauchbar -- deshalb hier in beide Richtungen
 * geprueft.
 */
class ConfigSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("Quellen")
    class Sources {

        @Test
        @DisplayName("Eine Pfad-Quelle uebersteht die Umwandlung unveraendert")
        void roundTripsLocalPath() {
            var original = new SourceConfig.LocalPath(
                    List.of("/sources/fotos", "/sources/dokumente"),
                    List.of("*.tmp", "node_modules"),
                    true);

            String json = objectMapper.writeValueAsString(original);
            var restored = objectMapper.readValue(json, SourceConfig.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.type()).isEqualTo(SourceType.LOCAL_PATH);
        }

        @Test
        @DisplayName("Die Typkennung steht im JSON, damit sich der Typ wiederfinden laesst")
        void writesTypeDiscriminator() {
            String json = objectMapper.writeValueAsString(
                    new SourceConfig.LocalPath(List.of("/a"), List.of(), false));

            assertThat(json).contains("\"type\":\"LOCAL_PATH\"");
        }

        @Test
        @DisplayName("Relative Pfade werden beim Anlegen abgelehnt")
        void rejectsRelativePaths() {
            // Beim Anlegen, nicht beim ersten naechtlichen Lauf.
            assertThatThrownBy(() -> new SourceConfig.LocalPath(List.of("relativ"), List.of(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("absolut");
        }

        @Test
        @DisplayName("Ein Blockgeraet uebersteht die Umwandlung unveraendert")
        void roundTripsBlockDevice() {
            var original = new SourceConfig.BlockDevice("/dev/sdb", "systemplatte.img", true);

            var restored = objectMapper.readValue(objectMapper.writeValueAsString(original),
                    SourceConfig.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.type()).isEqualTo(SourceType.BLOCK_DEVICE);
        }

        @Test
        @DisplayName("Ohne Angabe bekommt das Abbild den Namen des Geraets")
        void derivesImageName() {
            assertThat(new SourceConfig.BlockDevice("/dev/sdb", null, false).imageName())
                    .isEqualTo("sdb.img");
        }

        @Test
        @DisplayName("Was nicht unter /dev liegt, ist kein Blockgeraet")
        void rejectsNonDevicePath() {
            assertThatThrownBy(() -> new SourceConfig.BlockDevice("/home/remo/platte.img", null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("/dev");
        }

        @Test
        @DisplayName("Rueckspruenge im Geraetepfad werden abgelehnt")
        void rejectsTraversalInDevicePath() {
            assertThatThrownBy(() -> new SourceConfig.BlockDevice("/dev/../etc/shadow", null, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Der Name des Abbilds ist ein Dateiname, kein Pfad")
        void rejectsPathAsImageName() {
            // Sonst schriebe dd irgendwohin, nur nicht ins Zwischenverzeichnis.
            assertThatThrownBy(() -> new SourceConfig.BlockDevice("/dev/sdb", "../../etc/passwd", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dateiname");
        }

        @Test
        @DisplayName("Die Listen sind nach dem Anlegen unveraenderlich")
        void listsAreImmutable() {
            var config = new SourceConfig.LocalPath(new java.util.ArrayList<>(List.of("/a")), null, false);

            assertThat(config.excludes()).isEmpty();
            assertThatThrownBy(() -> config.paths().add("/b"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Ziele")
    class Targets {

        @Test
        @DisplayName("Ein lokales Ziel uebersteht die Umwandlung unveraendert")
        void roundTripsLocalPath() {
            var passwordId = UUID.randomUUID();
            var original = new TargetConfig.LocalPath("/mnt/nas/backups", passwordId);

            var restored = objectMapper.readValue(objectMapper.writeValueAsString(original),
                    TargetConfig.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.repositoryPasswordCredentialId()).isEqualTo(passwordId);
        }

        @Test
        @DisplayName("Ein S3-Ziel uebersteht die Umwandlung unveraendert")
        void roundTripsS3() {
            var original = new TargetConfig.S3("https://s3.eu-central-1.amazonaws.com",
                    "meine-backups", "server1", UUID.randomUUID(), UUID.randomUUID());

            var restored = objectMapper.readValue(objectMapper.writeValueAsString(original),
                    TargetConfig.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.type()).isEqualTo(TargetType.S3);
        }

        @Test
        @DisplayName("Zugangsdaten stehen nur als Verweis, nie im Klartext")
        void storesOnlyCredentialReferences() {
            // Die Konfiguration geht unveraendert an die API. Ein Klartextfeld hier waere
            // ein Leck, das keine spaetere Filterung mehr zuverlaessig schliesst.
            String json = objectMapper.writeValueAsString(new TargetConfig.S3(
                    "https://s3.amazonaws.com", "bucket", null, UUID.randomUUID(), UUID.randomUUID()));

            assertThat(json).doesNotContainIgnoringCase("secret").doesNotContainIgnoringCase("password\":\"");
            assertThat(json).contains("credentialId");
        }

        @Test
        @DisplayName("Ein S3-Ziel ohne Zugangsdaten wird abgelehnt")
        void rejectsS3WithoutCredentials() {
            assertThatThrownBy(() -> new TargetConfig.S3("https://s3.amazonaws.com", "bucket",
                    null, null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Zugangsdaten");
        }

        @Test
        @DisplayName("Unsinnige Adressen und Bucket-Namen werden abgelehnt")
        void rejectsInvalidEndpointAndBucket() {
            assertThatThrownBy(() -> new TargetConfig.S3("s3.amazonaws.com", "bucket", null,
                    UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http");

            assertThatThrownBy(() -> new TargetConfig.S3("https://s3.amazonaws.com", "bucket/pfad",
                    null, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Schraegstrich");
        }
    }

    @Nested
    @DisplayName("Modus")
    class Modes {

        @Test
        @DisplayName("Nur der restic-Modus kennt Snapshots")
        void onlyResticSupportsSnapshots() {
            // Davon haengt ab, ob die Oberflaeche Aufbewahrung und Snapshot-Browser anbietet.
            assertThat(TargetMode.RESTIC.supportsSnapshots()).isTrue();
            assertThat(TargetMode.MIRROR.supportsSnapshots()).isFalse();
        }

        @Test
        @DisplayName("Noch nicht umgesetzte Typen sind als solche erkennbar")
        void marksUnimplementedTypes() {
            // Damit die Oberflaeche sie ausgraut, statt ein Anlegen zuzulassen, das
            // beim ersten Lauf scheitert.
            assertThat(SourceType.LOCAL_PATH.isImplemented()).isTrue();
            assertThat(SourceType.POSTGRES.isImplemented()).isTrue();
            assertThat(SourceType.GITHUB.isImplemented()).isTrue();
            assertThat(SourceType.S3.isImplemented()).isTrue();
            assertThat(SourceType.SFTP.isImplemented()).isTrue();
            assertThat(SourceType.BLOCK_DEVICE.isImplemented()).isTrue();
            // Noch offen: reines FTP.
            assertThat(SourceType.FTP.isImplemented()).isFalse();
            assertThat(TargetType.S3.isImplemented()).isTrue();
            assertThat(TargetType.SFTP.isImplemented()).isFalse();
        }
    }
}
