package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.catalog.SourceConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Der Bau der rclone-Konfiguration.
 *
 * <p>Sie ist selbst ein Geheimnis: Schluessel und Passwoerter stehen darin. Geprueft wird
 * deshalb vor allem, dass nichts davon auf die Kommandozeile rutscht.
 */
class RcloneConfigTest {

    private static SourceConfig.S3 s3(String prefix) {
        return new SourceConfig.S3("https://minio.example:9000", "meine-daten", prefix, null,
                UUID.randomUUID());
    }

    private static SourceConfig.Sftp sftp() {
        return new SourceConfig.Sftp("nas.example", 22, "backup", "/volume1/daten",
                "nas.example ssh-ed25519 AAAAC3NzaC1lZDI1NTE5", UUID.randomUUID());
    }

    @Nested
    @DisplayName("S3")
    class S3Section {

        @Test
        @DisplayName("Schluessel stehen in der Konfiguration, nicht im Kommando")
        void keysStayInTheConfiguration() {
            String config = RcloneConfig.forS3(s3(null), "AKIAIOSFODNN7EXAMPLE", "geheimer-schluessel");

            assertThat(config).contains("type = s3")
                    .contains("access_key_id = AKIAIOSFODNN7EXAMPLE")
                    .contains("secret_access_key = geheimer-schluessel")
                    .contains("endpoint = https://minio.example:9000");

            assertThat(String.join(" ", RcloneConfig.copyCommand("meine-daten", "/staging")))
                    .doesNotContain("geheimer-schluessel")
                    .doesNotContain("AKIAIOSFODNN7EXAMPLE");
        }

        @Test
        @DisplayName("Ohne Praefix wird das ganze Bucket geholt")
        void wholeBucketWithoutPrefix() {
            assertThat(RcloneConfig.s3Path(s3(null))).isEqualTo("meine-daten");
            assertThat(RcloneConfig.s3Path(s3("  "))).isEqualTo("meine-daten");
        }

        @Test
        @DisplayName("Ein Praefix wird angehaengt, mit oder ohne fuehrenden Schraegstrich")
        void prefixIsAppended() {
            assertThat(RcloneConfig.s3Path(s3("fotos"))).isEqualTo("meine-daten/fotos");
            assertThat(RcloneConfig.s3Path(s3("/fotos"))).isEqualTo("meine-daten/fotos");
        }

        @Test
        @DisplayName("Eine Adresse ohne Schema wird abgelehnt")
        void rejectsEndpointWithoutScheme() {
            // Beim Anlegen, nicht beim ersten naechtlichen Lauf.
            assertThatThrownBy(() -> new SourceConfig.S3("minio.example", "bucket", null, null,
                    UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http");
        }
    }

    @Nested
    @DisplayName("SFTP")
    class SftpSection {

        @Test
        @DisplayName("Schluessel und bekannte Hosts stehen als Dateien, nicht als Werte")
        void filesInsteadOfValues() {
            String config = RcloneConfig.forSftpWithKey(sftp(), "sftp-key");

            assertThat(config).contains("type = sftp")
                    .contains("host = nas.example")
                    .contains("key_file = /run/secrets/sftp-key")
                    .contains("known_hosts_file = /run/secrets/known_hosts");
        }

        @Test
        @DisplayName("Ohne bekannten Hostschluessel wird gar nicht erst verbunden")
        void refusesWithoutHostKey() {
            // Ein Backup, das jeden Serverschluessel akzeptiert, laedt seine Daten im
            // Zweifel bei jemand anderem hoch -- und merkt es nicht.
            assertThatThrownBy(() -> new SourceConfig.Sftp("nas.example", 22, "backup", "/daten",
                    "  ", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Hostschluessel");
        }

        @Test
        @DisplayName("Ein relativer Pfad wird abgelehnt")
        void rejectsRelativePath() {
            assertThatThrownBy(() -> new SourceConfig.Sftp("nas.example", 22, "backup", "daten",
                    "nas.example ssh-ed25519 AAAA", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("absolut");
        }
    }

    @Nested
    @DisplayName("Kommando")
    class Command {

        @Test
        @DisplayName("Kopiert, statt abzugleichen")
        void copiesInsteadOfSyncing() {
            // sync loescht am Ziel, was an der Quelle fehlt. Bei einem Backup ist das die
            // falsche Richtung -- und das Arbeitsverzeichnis ist ohnehin frisch.
            List<String> command = RcloneConfig.copyCommand("bucket/pfad", "/staging");

            assertThat(command).contains("copy").doesNotContain("sync");
            assertThat(command).containsSequence("--config", "/run/secrets/rclone.conf");
            assertThat(command).contains("quelle:bucket/pfad", "/staging");
        }
    }
}
