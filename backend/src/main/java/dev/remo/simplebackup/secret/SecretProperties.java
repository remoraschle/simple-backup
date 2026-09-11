package dev.remo.simplebackup.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param masterKey     Base64-kodierter 256-Bit-Schluessel, direkt aus der Umgebung.
 * @param masterKeyFile Pfad zu einer Datei mit demselben Inhalt. Fuer Docker Secrets
 *                      gedacht und der Umgebungsvariablen vorzuziehen, weil der Wert
 *                      dann nicht in der Prozessumgebung steht.
 * @param keyVersion    Version, mit der neue Datensaetze verschluesselt werden.
 *                      Aeltere Datensaetze behalten ihre Version, bis sie neu
 *                      geschrieben werden; das ermoeglicht Rotation ohne Stillstand.
 */
@ConfigurationProperties(prefix = "simplebackup.secrets")
public record SecretProperties(String masterKey, String masterKeyFile, int keyVersion) {
}
