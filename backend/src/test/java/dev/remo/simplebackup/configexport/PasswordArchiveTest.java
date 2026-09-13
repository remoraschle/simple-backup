package dev.remo.simplebackup.configexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die Huelle des Konfigurationsarchivs.
 *
 * <p>Geprueft wird nicht nur, dass sich das Archiv wieder oeffnen laesst, sondern vor allem,
 * dass es sich bei falschem Passwort und bei nachtraeglicher Veraenderung <b>weigert</b>.
 * Ein Archiv, das eine veraenderte Datei klaglos einspielt, waere ein Einfallstor: Darin
 * stehen die Adressen aller Ziele und die Zugangsdaten dazu.
 */
class PasswordArchiveTest {

    private static final char[] PASSWORD = "ein-langes-archivpasswort".toCharArray();

    private final PasswordArchive archive = new PasswordArchive();

    private static byte[] content() {
        return "{\"credentials\":[{\"secret\":\"geheim\"}]}".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Was verschluesselt wurde, kommt unveraendert zurueck")
    void roundTrips() {
        byte[] sealed = archive.seal(content(), PASSWORD);

        assertThat(archive.open(sealed, PASSWORD)).isEqualTo(content());
    }

    @Test
    @DisplayName("Der Klartext steht nicht im Archiv")
    void archiveRevealsNothing() {
        byte[] sealed = archive.seal(content(), PASSWORD);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("geheim");
    }

    @Test
    @DisplayName("Zweimal dasselbe ergibt zwei verschiedene Archive")
    void usesFreshSaltAndIv() {
        // Gleiches Ergebnis hiesse festes Salz oder festes IV -- bei GCM waere die
        // Wiederverwendung eines IV mit demselben Schluessel ein echter Bruch.
        assertThat(archive.seal(content(), PASSWORD)).isNotEqualTo(archive.seal(content(), PASSWORD));
    }

    @Test
    @DisplayName("Mit falschem Passwort geht es nicht auf")
    void rejectsWrongPassword() {
        byte[] sealed = archive.seal(content(), PASSWORD);

        assertThatThrownBy(() -> archive.open(sealed, "ein-anderes-archivpasswort".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Falsches Passwort");
    }

    @Test
    @DisplayName("Ein einziges veraendertes Byte macht das Archiv unbrauchbar")
    void detectsTampering() {
        byte[] sealed = archive.seal(content(), PASSWORD);
        byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[tampered.length - 5] = (byte) (tampered[tampered.length - 5] + 1);

        assertThatThrownBy(() -> archive.open(tampered, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Auch eine Veraenderung am Salz faellt auf")
    void detectsTamperedSalt() {
        // Das Salz steht unverschluesselt im Kopf. Wer es aendert, bekommt einen anderen
        // Schluessel -- und GCM merkt es.
        byte[] sealed = archive.seal(content(), PASSWORD);
        int saltStart = PasswordArchive.MAGIC.length + 1;
        sealed[saltStart] = (byte) (sealed[saltStart] + 1);

        assertThatThrownBy(() -> archive.open(sealed, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Eine fremde Datei wird erkannt, bevor entschluesselt wird")
    void rejectsForeignFile() {
        byte[] foreign = "PK irgendein ZIP-Archiv".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> archive.open(foreign, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kein Archiv dieser Anwendung");
    }

    @Test
    @DisplayName("Ein Archiv einer anderen Formatversion wird nicht geraten")
    void rejectsUnknownVersion() {
        byte[] sealed = archive.seal(content(), PASSWORD);
        sealed[PasswordArchive.MAGIC.length] = (byte) (PasswordArchive.FORMAT_VERSION + 1);

        assertThatThrownBy(() -> archive.open(sealed, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archivformat");
    }

    @Test
    @DisplayName("Ein zu kurzes Passwort wird beim Anlegen abgelehnt")
    void rejectsShortPassword() {
        // Beim Anlegen, nicht erst beim Oeffnen: Danach liegt die Datei schon irgendwo.
        assertThatThrownBy(() -> archive.seal(content(), "kurz".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 Zeichen");
    }

    @Test
    @DisplayName("Ein abgeschnittenes Archiv wird abgelehnt statt zu stuerzen")
    void rejectsTruncatedArchive() {
        byte[] sealed = archive.seal(content(), PASSWORD);

        assertThatThrownBy(() -> archive.open(Arrays.copyOf(sealed, 10), PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
