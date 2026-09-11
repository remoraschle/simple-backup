package dev.remo.simplebackup.secret;

/**
 * Ein verschluesselter Wert samt allem, was zum Entschluesseln noetig ist -- ausser dem
 * Masterkey selbst.
 *
 * @param wrappedDek  der mit dem Masterkey verschluesselte Data Key
 * @param dekIv       Initialisierungsvektor des Wrap-Vorgangs
 * @param ciphertext  der mit dem Data Key verschluesselte Klartext
 * @param payloadIv   Initialisierungsvektor der Nutzdaten
 * @param keyVersion  Version des verwendeten Masterkeys
 */
public record EncryptedSecret(
        byte[] wrappedDek,
        byte[] dekIv,
        byte[] ciphertext,
        byte[] payloadIv,
        int keyVersion) {
}
