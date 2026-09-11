package dev.remo.simplebackup.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Die Uebersetzung ist reine Rechnerei -- und genau deshalb dicht getestet: Ein Fehler hier
 * faellt sonst erst beim ersten naechtlichen Lauf auf, und zwar als Backup, das entweder ins
 * Leere greift oder das falsche Verzeichnis erwischt.
 */
class MountTranslatorTest {

    /** Entspricht einer typischen Compose-Datei. */
    private static final MountTranslator TRANSLATOR = new MountTranslator(List.of(
            new VolumeMount("/srv/fotos", "/sources/fotos", true, false),
            new VolumeMount("/mnt", "/mnt", false, false),
            new VolumeMount("/mnt/nas", "/mnt/nas", false, false),
            new VolumeMount("staging", "/var/lib/simple-backup/staging", false, true)));

    @Nested
    @DisplayName("Aufloesung")
    class Resolution {

        @Test
        @DisplayName("Ein genau eingehaengter Pfad wird auf seinen Host-Pfad abgebildet")
        void translatesExactMatch() {
            var mount = TRANSLATOR.translate("/sources/fotos", true);

            assertThat(mount.source()).isEqualTo("/srv/fotos");
            assertThat(mount.target()).isEqualTo("/sources/fotos");
            assertThat(mount.readOnly()).isTrue();
        }

        @Test
        @DisplayName("Ein Unterpfad wird gezielt eingehaengt, nicht der ganze Mount")
        void translatesSubPathPrecisely() {
            // Der Runner soll nur sehen, was er braucht.
            var mount = TRANSLATOR.translate("/sources/fotos/2024/urlaub", true);

            assertThat(mount.source()).isEqualTo("/srv/fotos/2024/urlaub");
            assertThat(mount.target()).isEqualTo("/sources/fotos/2024/urlaub");
        }

        @Test
        @DisplayName("Der spezifischste Mount gewinnt")
        void mostSpecificMountWins() {
            // Sowohl /mnt als auch /mnt/nas sind eingehaengt. Ein Treffer auf /mnt waere
            // hier zwar auch aufloesbar, aber der falsche Mount.
            var mount = TRANSLATOR.translate("/mnt/nas/backups", false);

            assertThat(mount.source()).isEqualTo("/mnt/nas/backups");

            // Zur Gegenprobe: ausserhalb von /mnt/nas greift wieder /mnt.
            assertThat(TRANSLATOR.translate("/mnt/usb/platte", false).source()).isEqualTo("/mnt/usb/platte");
        }

        @Test
        @DisplayName("Ein benanntes Volume wird immer als Ganzes eingehaengt")
        void mountsNamedVolumeCompletely() {
            // Docker kann kein Unterverzeichnis eines Volumes einhaengen. Der Unterpfad ist
            // im Runner trotzdem erreichbar.
            var mount = TRANSLATOR.translate("/var/lib/simple-backup/staging/lauf-42", false);

            assertThat(mount.namedVolume()).isTrue();
            assertThat(mount.source()).isEqualTo("staging");
            assertThat(mount.target()).isEqualTo("/var/lib/simple-backup/staging");
            assertThat(mount.toBindSpec()).isEqualTo("staging:/var/lib/simple-backup/staging");
        }

        @Test
        @DisplayName("Schreibschutz wird uebernommen")
        void appliesReadOnlyFlag() {
            assertThat(TRANSLATOR.translate("/mnt/nas/ziel", false).readOnly()).isFalse();
            assertThat(TRANSLATOR.translate("/mnt/nas/ziel", true).readOnly()).isTrue();
        }
    }

    @Nested
    @DisplayName("Ablehnung")
    class Rejection {

        @Test
        @DisplayName("Ein nicht eingehaengter Pfad wird abgelehnt, nicht geraten")
        void rejectsUnmountedPath() {
            assertThatThrownBy(() -> TRANSLATOR.translate("/etc/shadow", true))
                    .isInstanceOf(PathTranslationException.class)
                    .hasMessageContaining("nicht eingehaengt")
                    // Die Meldung muss sagen, was zu tun ist, und was zur Verfuegung steht.
                    .hasMessageContaining("Compose-Datei")
                    .hasMessageContaining("/sources/fotos");
        }

        @Test
        @DisplayName("Ein Pfad mit .. wird abgelehnt")
        void rejectsParentTraversal() {
            // Ohne diese Pruefung liesse sich ein nie eingehaengtes Verzeichnis sichern --
            // oder beim Wiederherstellen ueberschreiben.
            assertThatThrownBy(() -> TRANSLATOR.translate("/sources/fotos/../../etc/shadow", true))
                    .isInstanceOf(PathTranslationException.class)
                    .hasMessageContaining("..");
        }

        @Test
        @DisplayName("Ein aehnlich beginnender Pfad zaehlt nicht als Treffer")
        void respectsPathBoundaries() {
            // /sources/fotos-privat liegt NICHT in /sources/fotos, auch wenn der reine
            // Zeichenkettenvergleich das nahelegt.
            assertThatThrownBy(() -> TRANSLATOR.translate("/sources/fotos-privat", true))
                    .isInstanceOf(PathTranslationException.class);
        }

        @Test
        @DisplayName("Relative und leere Pfade werden abgelehnt")
        void rejectsRelativeAndBlankPaths() {
            assertThatThrownBy(() -> TRANSLATOR.translate("sources/fotos", true))
                    .isInstanceOf(PathTranslationException.class)
                    .hasMessageContaining("absolut");

            assertThatThrownBy(() -> TRANSLATOR.translate("   ", true))
                    .isInstanceOf(PathTranslationException.class);

            assertThatThrownBy(() -> TRANSLATOR.translate(null, true))
                    .isInstanceOf(PathTranslationException.class);
        }

        @Test
        @DisplayName("Ein Nullbyte im Pfad wird abgelehnt")
        void rejectsNullByte() {
            assertThatThrownBy(() -> TRANSLATOR.translate("/sources/fotos\0/x", true))
                    .isInstanceOf(PathTranslationException.class);
        }

        @Test
        @DisplayName("Ohne jede Einhaengung ist die Meldung trotzdem verstaendlich")
        void explainsEmptyMountTable() {
            var empty = new MountTranslator(List.of());

            assertThatThrownBy(() -> empty.translate("/irgendwo", true))
                    .isInstanceOf(PathTranslationException.class)
                    .hasMessageContaining("(keine)");
        }
    }

    @Nested
    @DisplayName("Vorabpruefung")
    class Probing {

        @Test
        @DisplayName("canTranslate meldet aufloesbare Pfade")
        void reportsTranslatablePaths() {
            // Wird beim Anlegen einer Quelle verwendet, damit Fehlkonfiguration sofort
            // auffaellt statt beim ersten Lauf.
            assertThat(TRANSLATOR.canTranslate("/sources/fotos/2024")).isTrue();
            assertThat(TRANSLATOR.canTranslate("/etc/shadow")).isFalse();
        }

        @Test
        @DisplayName("canTranslate wirft nicht, auch bei unsinniger Eingabe")
        void neverThrows() {
            assertThat(TRANSLATOR.canTranslate("relativ")).isFalse();
            assertThat(TRANSLATOR.canTranslate("")).isFalse();
            assertThat(TRANSLATOR.canTranslate("/a/../b")).isFalse();
        }
    }
}
