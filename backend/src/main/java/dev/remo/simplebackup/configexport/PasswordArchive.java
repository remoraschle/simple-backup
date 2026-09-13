package dev.remo.simplebackup.configexport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verschluesselt ein Archiv mit einem selbst gewaehlten Passwort.
 *
 * <p>Bewusst unabhaengig vom Masterkey: Dieses Archiv ist genau fuer den Fall da, dass der
 * Masterkey weg ist. Eines, das ihn braucht, waere nutzlos.
 *
 * <p>Der Schluessel entsteht aus dem Passwort ueber PBKDF2 mit hoher Rundenzahl. Das
 * verlangsamt das Durchprobieren von Passwoertern -- und ein solches Archiv liegt
 * naturgemaess dort, wo es jemand finden kann.
 *
 * <p>Aufbau der Datei: Kennung, Version, Salz, IV, Schluesseltext. Alles, was zum
 * Entschluesseln noetig ist, steht darin; nur das Passwort nicht.
 */
@Component
class PasswordArchive {

    /** Damit sich eine fremde Datei erkennen laesst, bevor irgendetwas entschluesselt wird. */
    static final byte[] MAGIC = "SBEXP".getBytes(StandardCharsets.US_ASCII);

    static final int FORMAT_VERSION = 1;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;

    /**
     * Rundenzahl fuer die Schluesselableitung.
     *
     * <p>Hoch genug, dass das Durchprobieren teuer wird, und niedrig genug, dass ein Export
     * nicht spuerbar haengt -- auf heutiger Hardware liegt das im Bereich von Sekundenbruchteilen.
     */
    private static final int ITERATIONS = 600_000;

    /** Kuerzer ergibt kein Archiv, das man irgendwo liegen lassen moechte. */
    static final int MINIMUM_PASSWORD_LENGTH = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    byte[] seal(byte[] plaintext, char[] password) {
        requirePassword(password);
        byte[] salt = random(SALT_LENGTH_BYTES);
        byte[] iv = random(IV_LENGTH_BYTES);

        byte[] key = deriveKey(password, salt);
        try {
            byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, key, iv, plaintext);

            byte[] archive = new byte[MAGIC.length + 1 + salt.length + iv.length + ciphertext.length];
            int offset = 0;

            System.arraycopy(MAGIC, 0, archive, offset, MAGIC.length);
            offset += MAGIC.length;
            archive[offset++] = (byte) FORMAT_VERSION;
            System.arraycopy(salt, 0, archive, offset, salt.length);
            offset += salt.length;
            System.arraycopy(iv, 0, archive, offset, iv.length);
            offset += iv.length;
            System.arraycopy(ciphertext, 0, archive, offset, ciphertext.length);

            return archive;

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Archiv liess sich nicht verschluesseln", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    byte[] open(byte[] archive, char[] password) {
        requirePassword(password);

        int headerLength = MAGIC.length + 1 + SALT_LENGTH_BYTES + IV_LENGTH_BYTES;
        if (archive.length <= headerLength
                || !Arrays.equals(Arrays.copyOf(archive, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("Das ist kein Archiv dieser Anwendung");
        }

        int version = archive[MAGIC.length];
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Archivformat %d wird nicht unterstuetzt, erwartet wird %d"
                            .formatted(version, FORMAT_VERSION));
        }

        int offset = MAGIC.length + 1;
        byte[] salt = Arrays.copyOfRange(archive, offset, offset + SALT_LENGTH_BYTES);
        offset += SALT_LENGTH_BYTES;
        byte[] iv = Arrays.copyOfRange(archive, offset, offset + IV_LENGTH_BYTES);
        offset += IV_LENGTH_BYTES;
        byte[] ciphertext = Arrays.copyOfRange(archive, offset, archive.length);

        byte[] key = deriveKey(password, salt);
        try {
            return transform(Cipher.DECRYPT_MODE, key, iv, ciphertext);

        } catch (GeneralSecurityException e) {
            // GCM erkennt sowohl ein falsches Passwort als auch eine veraenderte Datei.
            // Welches von beidem es war, laesst sich nicht sagen -- und beides ist ein Grund,
            // nichts einzuspielen.
            throw new IllegalArgumentException(
                    "Falsches Passwort, oder die Datei wurde veraendert", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Das Passwort muss mindestens %d Zeichen haben".formatted(MINIMUM_PASSWORD_LENGTH));
        }
    }

    /**
     * Liefert die rohen Schluesselbytes und keinen {@code SecretKey}.
     *
     * <p>Ein {@link SecretKeySpec} gibt bei {@code getEncoded()} jedes Mal eine Kopie heraus;
     * die Kopie zu ueberschreiben braechte nichts. Nur wer das Original in der Hand haelt,
     * kann es nach Gebrauch wirklich loeschen.
     */
    private static byte[] deriveKey(char[] password, byte[] salt) {
        var spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
                    .getEncoded();

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Schluesselableitung fehlgeschlagen", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] transform(int mode, byte[] key, byte[] iv, byte[] input)
            throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(input);
    }

    private byte[] random(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
