package dev.remo.simplebackup.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Liefert den Masterkey, mit dem die Data Keys der einzelnen Zugangsdaten gewrappt werden.
 *
 * <p>Der Schluessel steht ausschliesslich in der Umgebung, niemals in der Datenbank. Geht er
 * verloren, sind alle gespeicherten Zugangsdaten unwiederbringlich verloren -- deshalb
 * gehoert er in den Passwortmanager und in das Restore-Runbook.
 *
 * <p>Fehlt der Schluessel, startet die Anwendung nicht. Ein Backup-Tool, das im Zweifel
 * unverschluesselt weiterlaeuft, waere die schlechtere Alternative.
 */
@Component
public class MasterKeyProvider {

    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;

    private final SecretKey masterKey;
    private final int keyVersion;

    MasterKeyProvider(SecretProperties properties) {
        this.masterKey = loadKey(properties);
        this.keyVersion = properties.keyVersion();
    }

    public SecretKey masterKey() {
        return masterKey;
    }

    /** Version, mit der neu geschriebene Datensaetze verschluesselt werden. */
    public int currentKeyVersion() {
        return keyVersion;
    }

    private static SecretKey loadKey(SecretProperties properties) {
        String encoded = readEncodedKey(properties);
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Der Masterkey ist kein gueltiges Base64. Erwartet werden 32 zufaellige Bytes, "
                            + "etwa erzeugt mit: openssl rand -base64 32", e);
        }
        if (raw.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "Der Masterkey muss %d Bytes lang sein, ist aber %d Bytes. Erzeugen mit: openssl rand -base64 32"
                            .formatted(REQUIRED_KEY_LENGTH_BYTES, raw.length));
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String readEncodedKey(SecretProperties properties) {
        boolean hasInlineKey = StringUtils.hasText(properties.masterKey());
        boolean hasKeyFile = StringUtils.hasText(properties.masterKeyFile());

        if (hasInlineKey && hasKeyFile) {
            throw new IllegalStateException(
                    "Es sind sowohl simplebackup.secrets.master-key als auch master-key-file gesetzt. "
                            + "Genau eine Quelle waehlen, sonst ist unklar, welcher Schluessel gilt.");
        }
        if (hasKeyFile) {
            Path path = Path.of(properties.masterKeyFile());
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Masterkey-Datei nicht lesbar: " + path, e);
            }
        }
        if (hasInlineKey) {
            return properties.masterKey();
        }
        throw new IllegalStateException("""
                Kein Masterkey konfiguriert. Ohne ihn koennen keine Zugangsdaten gespeichert werden.

                Schluessel erzeugen:   openssl rand -base64 32
                Danach entweder SIMPLEBACKUP_MASTER_KEY setzen oder -- besser --
                SIMPLEBACKUP_MASTER_KEY_FILE auf eine Datei mit diesem Inhalt zeigen lassen.

                Den Schluessel im Passwortmanager sichern: Ohne ihn sind alle gespeicherten
                Zugangsdaten verloren.""");
    }
}
