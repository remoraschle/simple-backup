package dev.remo.simplebackup.restic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResticRepositoryTest {

    @Nested
    @DisplayName("Lokales Repository")
    class LocalPath {

        @Test
        @DisplayName("Der Pfad ist die Adresse, das Passwort kommt aus einer Datei")
        void usesPathAsUrlAndPasswordFile() {
            var repository = ResticRepository.localPath("/mnt/nas/backups/fotos", "geheim");

            assertThat(repository.url()).isEqualTo("/mnt/nas/backups/fotos");
            assertThat(repository.environment())
                    .containsEntry("RESTIC_PASSWORD_FILE", "/run/secrets/restic-password");
            assertThat(repository.secretFiles()).containsEntry("restic-password", "geheim");
        }

        @Test
        @DisplayName("Das Passwort steht nie in einer Umgebungsvariablen")
        void neverPutsPasswordInEnvironment() {
            // RESTIC_PASSWORD waere ueber docker inspect dauerhaft lesbar.
            var repository = ResticRepository.localPath("/mnt/backup", "streng-geheim");

            assertThat(repository.environment().values()).noneMatch(value -> value.contains("streng-geheim"));
            assertThat(repository.environment()).doesNotContainKey("RESTIC_PASSWORD");
        }

        @Test
        @DisplayName("Ein relativer Pfad oder fehlendes Passwort wird abgelehnt")
        void rejectsInvalidInput() {
            assertThatThrownBy(() -> ResticRepository.localPath("mnt/backup", "geheim"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolut");

            assertThatThrownBy(() -> ResticRepository.localPath("/mnt/backup", ""))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repositoryPassword");
        }
    }

    @Nested
    @DisplayName("S3-Repository")
    class S3 {

        @Test
        @DisplayName("Adresse, Bucket und Pfad ergeben die restic-Adresse")
        void buildsS3Url() {
            var repository = ResticRepository.s3("https://s3.eu-central-1.amazonaws.com",
                    "meine-backups", "server1/fotos", "AKIAIOSFODNN7EXAMPLE", "geheimer-schluessel", "passwort");

            assertThat(repository.url())
                    .isEqualTo("s3:https://s3.eu-central-1.amazonaws.com/meine-backups/server1/fotos");
        }

        @Test
        @DisplayName("S3-Zugangsdaten gehen als Datei, nicht als Umgebungsvariable")
        void putsAwsCredentialsInFile() {
            // AWS_ACCESS_KEY_ID und AWS_SECRET_ACCESS_KEY waeren ueber docker inspect lesbar.
            var repository = ResticRepository.s3("https://s3.amazonaws.com", "bucket", "",
                    "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI", "passwort");

            assertThat(repository.environment())
                    .containsEntry("AWS_SHARED_CREDENTIALS_FILE", "/run/secrets/aws-credentials")
                    .doesNotContainKey("AWS_ACCESS_KEY_ID")
                    .doesNotContainKey("AWS_SECRET_ACCESS_KEY");

            assertThat(repository.secretFiles().get("aws-credentials"))
                    .contains("[default]")
                    .contains("aws_access_key_id = AKIAIOSFODNN7EXAMPLE")
                    .contains("aws_secret_access_key = wJalrXUtnFEMI");
        }

        @Test
        @DisplayName("Ein leerer Pfad ergibt eine Adresse ohne ueberzaehligen Schraegstrich")
        void handlesEmptyPrefix() {
            assertThat(ResticRepository.s3("https://s3.amazonaws.com", "bucket", null, "k", "s", "p").url())
                    .isEqualTo("s3:https://s3.amazonaws.com/bucket");

            assertThat(ResticRepository.s3("https://s3.amazonaws.com", "bucket", "  ", "k", "s", "p").url())
                    .isEqualTo("s3:https://s3.amazonaws.com/bucket");
        }

        @Test
        @DisplayName("Ueberzaehlige Schraegstriche werden vereinheitlicht")
        void normalizesSlashes() {
            assertThat(ResticRepository.s3("https://minio:9000/", "bucket", "/pfad/", "k", "s", "p").url())
                    .isEqualTo("s3:https://minio:9000/bucket/pfad");
        }

        @Test
        @DisplayName("Ein S3-kompatibler Dienst ueber HTTP wird unterstuetzt")
        void supportsPlainHttpForCompatibleServices() {
            // MinIO im eigenen Netz laeuft haeufig ohne TLS.
            assertThat(ResticRepository.s3("http://minio:9000", "backups", "", "k", "s", "p").url())
                    .isEqualTo("s3:http://minio:9000/backups");
        }

        @Test
        @DisplayName("Eine Adresse ohne Schema wird abgelehnt")
        void rejectsEndpointWithoutScheme() {
            // restic wuerde daraus eine unbrauchbare Adresse bauen und erst beim Lauf scheitern.
            assertThatThrownBy(() -> ResticRepository.s3("s3.amazonaws.com", "bucket", "", "k", "s", "p"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http:// oder https://");
        }

        @Test
        @DisplayName("Ein Bucket-Name mit Schraegstrich wird abgelehnt")
        void rejectsBucketWithSlash() {
            assertThatThrownBy(() -> ResticRepository.s3("https://s3.amazonaws.com", "bucket/unterpfad",
                    "", "k", "s", "p"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Schraegstrich");
        }

        @Test
        @DisplayName("Fehlende Zugangsdaten werden abgelehnt")
        void rejectsMissingCredentials() {
            assertThatThrownBy(() -> ResticRepository.s3("https://s3.amazonaws.com", "b", "", "", "s", "p"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Die Textdarstellung verraet keine Zugangsdaten")
    void toStringHidesCredentials() {
        var repository = ResticRepository.s3("https://s3.amazonaws.com", "bucket", "",
                "AKIAIOSFODNN7EXAMPLE", "streng-geheim", "auch-geheim");

        assertThat(repository.toString())
                .doesNotContain("streng-geheim")
                .doesNotContain("auch-geheim")
                .contains("s3:https://s3.amazonaws.com/bucket");
    }
}
