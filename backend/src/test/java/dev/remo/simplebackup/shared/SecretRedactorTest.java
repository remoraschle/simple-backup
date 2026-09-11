package dev.remo.simplebackup.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    private SecretRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new SecretRedactor();
    }

    @Test
    @DisplayName("Angemeldeter Wert verschwindet aus dem Text")
    void redactsRegisteredValue() {
        redactor.register("mein-sehr-geheimes-passwort");

        String result = redactor.redact("restic: Verbindung mit mein-sehr-geheimes-passwort fehlgeschlagen");

        assertThat(result).doesNotContain("mein-sehr-geheimes-passwort").contains("***");
    }

    @Test
    @DisplayName("Zu kurze Werte werden nicht angemeldet")
    void ignoresShortValues() {
        // Wuerde sonst harmlosen Text mitschwaerzen und die Logs unlesbar machen.
        redactor.register("abc");

        assertThat(redactor.redact("abc ist ein harmloser Text")).isEqualTo("abc ist ein harmloser Text");
    }

    @Test
    @DisplayName("GitHub-Token wird auch ohne Anmeldung erkannt")
    void redactsGitHubToken() {
        String result = redactor.redact("remote: Bad credentials for ghp_abcdefghijklmnopqrstuvwxyz0123456789");

        assertThat(result).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    @DisplayName("AWS-Zugriffsschluessel wird erkannt")
    void redactsAwsAccessKey() {
        assertThat(redactor.redact("key=AKIAIOSFODNN7EXAMPLE")).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    @DisplayName("Passwort in einer URL wird geschwaerzt, Host und Benutzer bleiben lesbar")
    void redactsUrlPasswordButKeepsContext() {
        String result = redactor.redact("Fehler bei sftp://backup:s3hrG3h3im@nas.local/volume1");

        assertThat(result).doesNotContain("s3hrG3h3im")
                .contains("sftp://backup:")
                .contains("@nas.local/volume1");
    }

    @Test
    @DisplayName("Privater Schluessel wird vollstaendig entfernt")
    void redactsPrivateKeyBlock() {
        String text = """
                Verwende Schluessel:
                -----BEGIN OPENSSH PRIVATE KEY-----
                b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB
                -----END OPENSSH PRIVATE KEY-----
                Verbindung aufgebaut.""";

        String result = redactor.redact(text);

        assertThat(result).doesNotContain("b3BlbnNzaC1rZXktdjEA")
                .contains("Verwende Schluessel:")
                .contains("Verbindung aufgebaut.");
    }

    @Test
    @DisplayName("Abgemeldeter Wert wird nicht mehr geschwaerzt")
    void unregisterStopsRedaction() {
        redactor.register("temporaerer-wert-123");
        redactor.unregister("temporaerer-wert-123");

        assertThat(redactor.redact("temporaerer-wert-123")).isEqualTo("temporaerer-wert-123");
    }

    @Test
    @DisplayName("Kommandozeilen werden argumentweise bereinigt")
    void redactsArgumentList() {
        redactor.register("repository-passwort-xyz");

        var result = redactor.redact(java.util.List.of("restic", "--password-file", "repository-passwort-xyz"));

        assertThat(result).containsExactly("restic", "--password-file", "***");
    }
}
