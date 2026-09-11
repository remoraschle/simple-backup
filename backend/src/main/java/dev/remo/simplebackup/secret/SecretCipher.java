package dev.remo.simplebackup.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Envelope-Verschluesselung mit AES-256-GCM.
 *
 * <p>Je Datensatz wird ein eigener Data Key erzeugt, der Klartext damit verschluesselt und
 * der Data Key seinerseits mit dem Masterkey gewrappt. Das hat zwei Vorteile gegenueber
 * direkter Verschluesselung mit dem Masterkey: Der Masterkey verschluesselt nur sehr wenige
 * Bytes, und eine Rotation muss spaeter nur die gewrappten Data Keys anfassen statt alle
 * Nutzdaten.
 *
 * <p>GCM liefert Vertraulichkeit und Integritaet zugleich -- ein manipulierter Datensatz
 * faellt beim Entschluesseln auf, statt stillschweigend Unsinn zu liefern.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int DATA_KEY_LENGTH_BITS = 256;

    private final MasterKeyProvider masterKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    SecretCipher(MasterKeyProvider masterKeyProvider) {
        this.masterKeyProvider = masterKeyProvider;
    }

    public EncryptedSecret encrypt(String plaintext) {
        try {
            SecretKey dataKey = generateDataKey();

            byte[] payloadIv = randomIv();
            byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, dataKey, payloadIv,
                    plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] dekIv = randomIv();
            byte[] wrappedDek = transform(Cipher.ENCRYPT_MODE, masterKeyProvider.masterKey(), dekIv,
                    dataKey.getEncoded());

            return new EncryptedSecret(wrappedDek, dekIv, ciphertext, payloadIv,
                    masterKeyProvider.currentKeyVersion());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschluesseln fehlgeschlagen", e);
        }
    }

    public String decrypt(EncryptedSecret secret) {
        byte[] dataKeyBytes = null;
        try {
            dataKeyBytes = transform(Cipher.DECRYPT_MODE, masterKeyProvider.masterKey(),
                    secret.dekIv(), secret.wrappedDek());
            SecretKey dataKey = new SecretKeySpec(dataKeyBytes, "AES");

            byte[] plaintext = transform(Cipher.DECRYPT_MODE, dataKey, secret.payloadIv(), secret.ciphertext());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Entschluesseln fehlgeschlagen. Moegliche Ursachen: falscher Masterkey "
                            + "(Schluesselversion %d erwartet) oder manipulierter Datensatz."
                                    .formatted(secret.keyVersion()), e);
        } finally {
            // Den Data Key nicht laenger als noetig im Speicher halten.
            if (dataKeyBytes != null) {
                Arrays.fill(dataKeyBytes, (byte) 0);
            }
        }
    }

    private SecretKey generateDataKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(DATA_KEY_LENGTH_BITS, secureRandom);
        return generator.generateKey();
    }

    private byte[] randomIv() {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        return iv;
    }

    private static byte[] transform(int mode, SecretKey key, byte[] iv, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(input);
    }
}
