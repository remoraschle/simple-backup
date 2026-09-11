package dev.remo.simplebackup.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new SecretCipher(new MasterKeyProvider(new SecretProperties(MASTER_KEY, null, 1)));
    }

    @Test
    @DisplayName("Verschluesselter Text laesst sich unveraendert zurueckgewinnen")
    void roundTrip() {
        String plaintext = "ghp_einGeheimesGitHubTokenMitUmlautenÄÖÜ";

        String result = cipher.decrypt(cipher.encrypt(plaintext));

        assertThat(result).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Gleicher Klartext ergibt unterschiedliche Chiffrate")
    void producesDistinctCiphertexts() {
        // Andernfalls liesse sich aus der Datenbank ablesen, welche Zugaenge dasselbe
        // Passwort verwenden -- und das ist bereits eine verwertbare Information.
        EncryptedSecret first = cipher.encrypt("dasselbe Passwort");
        EncryptedSecret second = cipher.encrypt("dasselbe Passwort");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.wrappedDek()).isNotEqualTo(second.wrappedDek());
        assertThat(first.payloadIv()).isNotEqualTo(second.payloadIv());
    }

    @Test
    @DisplayName("Manipuliertes Chiffrat wird abgelehnt statt stillschweigend entschluesselt")
    void detectsTamperedCiphertext() {
        EncryptedSecret secret = cipher.encrypt("unveraenderlicher Wert");
        byte[] tampered = secret.ciphertext().clone();
        tampered[0] ^= 0x01;

        EncryptedSecret manipulated = new EncryptedSecret(
                secret.wrappedDek(), secret.dekIv(), tampered, secret.payloadIv(), secret.keyVersion());

        assertThatThrownBy(() -> cipher.decrypt(manipulated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Entschluesseln fehlgeschlagen");
    }

    @Test
    @DisplayName("Ein fremder Masterkey kann nicht entschluesseln")
    void rejectsForeignMasterKey() {
        EncryptedSecret secret = cipher.encrypt("Wert");

        byte[] otherKeyBytes = new byte[32];
        otherKeyBytes[0] = 0x42;
        SecretCipher otherCipher = new SecretCipher(new MasterKeyProvider(
                new SecretProperties(Base64.getEncoder().encodeToString(otherKeyBytes), null, 1)));

        assertThatThrownBy(() -> otherCipher.decrypt(secret)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Fehlender Masterkey verhindert den Start mit erklaerender Meldung")
    void failsWithoutMasterKey() {
        assertThatThrownBy(() -> new MasterKeyProvider(new SecretProperties(null, null, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    @DisplayName("Masterkey falscher Laenge wird abgelehnt")
    void rejectsWrongKeyLength() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new MasterKeyProvider(new SecretProperties(tooShort, null, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 Bytes");
    }
}
