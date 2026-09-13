package dev.remo.simplebackup.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Wer welche Platte roh lesen darf.
 *
 * <p>Die schaerfste Rechtefrage dieser Anwendung: Ein durchgereichtes Blockgeraet macht den
 * gesamten Inhalt eines Datentraegers lesbar, vorbei an allen Dateirechten. Deshalb wird
 * hier nicht nur geprueft, dass Erlaubtes durchkommt, sondern vor allem, dass alles andere
 * es nicht tut.
 */
class DeviceAccessTest {

    @Nested
    @DisplayName("Verfuegbarkeit")
    class Availability {

        @Test
        @DisplayName("Ohne Freigabe ist nichts verfuegbar")
        void unavailableWithoutAllowlist() {
            // Der Auslieferungszustand. Wer Platten sichern will, sagt es ausdruecklich.
            var access = new DeviceAccess(true, null, List.of());

            assertThat(access.available()).isFalse();
            assertThat(access.unavailableReason()).contains("simplebackup.engine.devices");
        }

        @Test
        @DisplayName("Ohne Wurzelrechte des Daemons ist nichts verfuegbar")
        void unavailableWhenRootless() {
            var access = new DeviceAccess(false, "Der Daemon läuft rootless.", List.of("/dev/sdb"));

            assertThat(access.available()).isFalse();
            assertThat(access.unavailableReason()).isEqualTo("Der Daemon läuft rootless.");
        }

        @Test
        @DisplayName("Mit Wurzelrechten und Freigabe geht es")
        void availableWhenBothConditionsHold() {
            var access = new DeviceAccess(true, null, List.of("/dev/sdb"));

            assertThat(access.available()).isTrue();
            assertThat(access.unavailableReason()).isNull();
            assertThat(access.allowed()).containsExactly("/dev/sdb");
        }

        @Test
        @DisplayName("Es gibt immer eine Begruendung, nie nur ein Nein")
        void alwaysExplainsItself() {
            // Ein ausgegrauter Eintrag ohne Erklaerung ist eine Sackgasse.
            assertThat(new DeviceAccess(true, null, List.of()).unavailableReason()).isNotBlank();
            assertThat(new DeviceAccess(false, "Rootless.", List.of()).unavailableReason()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Pruefung eines Geraets")
    class Requirement {

        private final DeviceAccess access = new DeviceAccess(true, null,
                List.of("/dev/sdb", "/dev/sdc"));

        @Test
        @DisplayName("Ein freigegebenes Geraet kommt durch")
        void allowsListedDevice() {
            assertThatCode(() -> access.require("/dev/sdc")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Ein nicht freigegebenes Geraet nicht")
        void rejectsUnlistedDevice() {
            assertThatThrownBy(() -> access.require("/dev/sda"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nicht freigegeben")
                    // Die Meldung nennt, was ginge -- sonst raet der Betreiber.
                    .hasMessageContaining("/dev/sdb");
        }

        @Test
        @DisplayName("Ein Praefix reicht nicht")
        void rejectsPrefixOfAllowedDevice() {
            // /dev/sdb1 ist eine andere Partition als /dev/sdb. Wer nur die eine freigibt,
            // hat nicht die andere gemeint.
            assertThatThrownBy(() -> access.require("/dev/sdb1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Ein Rueckweg ueber .. fuehrt nirgendwohin")
        void rejectsTraversal() {
            // Verglichen wird gegen die Liste, nicht gegen ein Muster -- deshalb greift
            // dieser Versuch von vornherein ins Leere.
            assertThatThrownBy(() -> access.require("/dev/../dev/sdb"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Ist die Funktion gar nicht verfuegbar, nennt die Meldung den Grund")
        void explainsWhenUnavailable() {
            var rootless = new DeviceAccess(false, "Der Daemon läuft rootless.", List.of("/dev/sdb"));

            assertThatThrownBy(() -> rootless.require("/dev/sdb"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rootless");
        }
    }
}
