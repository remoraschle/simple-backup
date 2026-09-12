package dev.remo.simplebackup.engine.restic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResticCommandsTest {

    @Nested
    @DisplayName("Sichern")
    class Backup {

        @Test
        @DisplayName("Ein einfaches Backup enthaelt Host, Kennzeichnung und Pfade")
        void buildsBasicBackupCommand() {
            var command = ResticCommands.backup(List.of("/quellen/fotos"), "plan-fotos", "fotos",
                    List.of(), null, false);

            assertThat(command).containsExactly(
                    "restic", "backup", "--json", "--host", "plan-fotos", "--tag", "fotos",
                    "--", "/quellen/fotos");
        }

        @Test
        @DisplayName("Der Host wird fest gesetzt und nicht dem Container ueberlassen")
        void alwaysSetsStableHost() {
            // restic schreibt den Hostnamen in jeden Snapshot. Im Container waere das eine
            // bei jedem Lauf neue Zufallskennung -- die Snapshots eines Plans waeren dann
            // nicht mehr als zusammengehoerig erkennbar.
            var command = ResticCommands.backup(List.of("/daten"), "mein-plan", null,
                    List.of(), null, false);

            assertThat(command).containsSubsequence("--host", "mein-plan");
        }

        @Test
        @DisplayName("Ohne Host wird nicht gesichert")
        void rejectsMissingHost() {
            assertThatThrownBy(() -> ResticCommands.backup(List.of("/daten"), null, "tag",
                    List.of(), null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("Ein Pfad, der mit einem Bindestrich beginnt, gilt als Pfad und nicht als Schalter")
        void separatesPathsFromOptions() {
            // Das doppelte Minus ist der Grund: Ohne es wuerde restic "-seltsam" als
            // unbekannten Schalter ablehnen.
            var command = ResticCommands.backup(List.of("/quellen/-seltsamer-name"), "plan", null,
                    List.of(), null, false);

            assertThat(command).containsSubsequence("--", "/quellen/-seltsamer-name");
            assertThat(command.getLast()).isEqualTo("/quellen/-seltsamer-name");
        }

        @Test
        @DisplayName("Ausschlussmuster werden einzeln uebergeben")
        void addsExcludePatternsIndividually() {
            var command = ResticCommands.backup(List.of("/daten"), "plan", "tag",
                    List.of("*.tmp", "node_modules", ""), "/etc/excludes.txt", false);

            assertThat(command).containsSubsequence("--exclude", "*.tmp");
            assertThat(command).containsSubsequence("--exclude", "node_modules");
            assertThat(command).containsSubsequence("--exclude-file", "/etc/excludes.txt");
            // Leere Muster wuerden alles ausschliessen und werden uebergangen.
            assertThat(command.stream().filter("--exclude"::equals).count()).isEqualTo(2);
        }

        @Test
        @DisplayName("Dateisystemgrenzen lassen sich beachten")
        void supportsOneFileSystem() {
            // Damit ein eingehaengtes Netzlaufwerk unterhalb der Quelle nicht unbemerkt
            // mitgesichert wird.
            assertThat(ResticCommands.backup(List.of("/"), "plan", null, List.of(), null, true))
                    .contains("--one-file-system");
        }

        @Test
        @DisplayName("Mehrere Pfade sind moeglich")
        void supportsMultiplePaths() {
            var command = ResticCommands.backup(List.of("/a", "/b", "/c"), "plan", null,
                    List.of(), null, false);

            assertThat(command.subList(command.indexOf("--") + 1, command.size()))
                    .containsExactly("/a", "/b", "/c");
        }

        @Test
        @DisplayName("Ohne Pfade wird nicht gesichert")
        void rejectsEmptyPaths() {
            assertThatThrownBy(() -> ResticCommands.backup(List.of(), "plan", null, List.of(), null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nichts zu sichern");
        }
    }

    @Nested
    @DisplayName("Aufbewahrung")
    class Forget {

        private static final RetentionRule RULE = new RetentionRule(null, null, 7, 4, 12, 3, null);

        @Test
        @DisplayName("forget begrenzt sich immer auf Host und Kennzeichnung des Plans")
        void alwaysScopesToPlan() {
            // Ohne diese Filter wuerde die Aufbewahrungsregel eines Plans die Snapshots
            // aller anderen Plaene im selben Repository mitloeschen. Das ist die
            // gefaehrlichste Stelle im ganzen Werkzeug.
            var command = ResticCommands.forget(RULE, "plan-fotos", "fotos", true, false);

            assertThat(command).containsSubsequence("--host", "plan-fotos");
            assertThat(command).containsSubsequence("--tag", "fotos");
        }

        @Test
        @DisplayName("Ohne Host oder Kennzeichnung wird nicht geloescht")
        void refusesToForgetWithoutScope() {
            assertThatThrownBy(() -> ResticCommands.forget(RULE, null, "fotos", true, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");

            assertThatThrownBy(() -> ResticCommands.forget(RULE, "plan", "", true, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tag");
        }

        @Test
        @DisplayName("Nur gesetzte Stufen werden uebergeben")
        void passesOnlyConfiguredLevels() {
            var command = ResticCommands.forget(new RetentionRule(null, null, 7, null, null, null, null),
                    "plan", "tag", false, false);

            assertThat(command).containsSubsequence("--keep-daily", "7");
            assertThat(command).doesNotContain("--keep-weekly", "--keep-monthly", "--keep-yearly");
        }

        @Test
        @DisplayName("keep-within wird in Tagen angegeben")
        void formatsKeepWithinAsDays() {
            var command = ResticCommands.forget(new RetentionRule(null, null, null, null, null, null, 30),
                    "plan", "tag", false, false);

            assertThat(command).containsSubsequence("--keep-within", "30d");
        }

        @Test
        @DisplayName("Prune laesst sich abschalten")
        void pruneIsOptional() {
            // Getrennt vom Backup ausfuehren: Ein Backup, das am Prune scheitert, hat
            // trotzdem gesichert.
            assertThat(ResticCommands.forget(RULE, "p", "t", true, false)).contains("--prune");
            assertThat(ResticCommands.forget(RULE, "p", "t", false, false)).doesNotContain("--prune");
        }

        @Test
        @DisplayName("Ein Probelauf zeigt nur an, was geloescht wuerde")
        void supportsDryRun() {
            // Grundlage der Bestaetigung in der Oberflaeche, bevor eine Regel verschaerft wird.
            assertThat(ResticCommands.forget(RULE, "p", "t", true, true)).contains("--dry-run");
        }

        @Test
        @DisplayName("Eine Regel, die nichts behaelt, wird schon beim Anlegen abgelehnt")
        void rejectsRuleThatKeepsNothing() {
            // Sie wuerde beim ersten Prune saemtliche Snapshots loeschen.
            assertThatThrownBy(() -> new RetentionRule(null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("saemtliche");

            assertThatThrownBy(() -> new RetentionRule(0, 0, 0, 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Weitere Kommandos")
    class Others {

        @Test
        @DisplayName("Die Pruefung liest wahlweise einen Anteil der Daten")
        void checkReadsDataSubset() {
            // Ohne diesen Anteil wird nur die Struktur geprueft -- es bliebe unbemerkt,
            // wenn Daten am Ziel verrottet sind.
            assertThat(ResticCommands.check(null)).containsExactly("restic", "check", "--json");
            assertThat(ResticCommands.check(5)).contains("--read-data-subset=5%");
        }

        @Test
        @DisplayName("Ein unsinniger Anteil wird abgelehnt")
        void rejectsInvalidSubset() {
            assertThatThrownBy(() -> ResticCommands.check(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ResticCommands.check(101)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Wiederherstellen braucht ein absolutes Ziel")
        void restoreRequiresAbsoluteTarget() {
            assertThat(ResticCommands.restore("latest", "/wiederhergestellt", List.of()))
                    .containsExactly("restic", "restore", "--json", "latest", "--target", "/wiederhergestellt");

            assertThatThrownBy(() -> ResticCommands.restore("latest", "relativ", List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolut");
        }

        @Test
        @DisplayName("Einzelne Pfade lassen sich gezielt wiederherstellen")
        void restoreSupportsIncludes() {
            assertThat(ResticCommands.restore("4bba301e", "/ziel", List.of("/srv/fotos/2024")))
                    .containsSubsequence("--include", "/srv/fotos/2024");
        }

        @Test
        @DisplayName("Snapshots lassen sich ungefiltert oder je Plan auflisten")
        void listsSnapshots() {
            assertThat(ResticCommands.snapshots(null, null))
                    .containsExactly("restic", "snapshots", "--json");
            assertThat(ResticCommands.snapshots("plan", "tag"))
                    .containsSubsequence("--host", "plan").containsSubsequence("--tag", "tag");
        }

        @Test
        @DisplayName("Eine einzelne Datei laesst sich ausgeben, ohne alles auszupacken")
        void dumpsSingleFile() {
            assertThat(ResticCommands.dump("latest", "/srv/fotos/urlaub.jpg"))
                    .containsExactly("restic", "dump", "latest", "/srv/fotos/urlaub.jpg");
        }

        @Test
        @DisplayName("Eine haengengebliebene Sperre laesst sich loesen")
        void unlocksRepository() {
            // Noetig nach einem Abbruch, bevor restic aufraeumen konnte.
            assertThat(ResticCommands.unlock()).containsExactly("restic", "unlock");
        }

        @Test
        @DisplayName("Alle Kommandos sind unveraenderlich")
        void commandsAreImmutable() {
            assertThatThrownBy(() -> ResticCommands.unlock().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ResticCommands.backup(List.of("/a"), "p", null, List.of(), null, false)
                    .add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
