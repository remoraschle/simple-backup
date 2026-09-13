package dev.remo.simplebackup.restic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Auswertung der Auflistungen.
 *
 * <p>Fehlertoleranz ist hier kein Luxus: restic mischt Meldungen in dieselbe Ausgabe und
 * ergaenzt mit jeder Version Felder. Wer daran scheitert, kann im Ernstfall nicht blaettern.
 */
class ResticListingParserTest {

    private final ResticListingParser parser = new ResticListingParser(new ObjectMapper());

    @Nested
    @DisplayName("Snapshots")
    class Snapshots {

        @Test
        @DisplayName("Liest die Liste, die restic als JSON-Array ausgibt")
        void readsJsonArray() {
            var snapshots = parser.parseSnapshots("""
                    [{"time":"2026-09-13T10:13:47.814652Z","tree":"abc","paths":["/daten"],
                      "hostname":"plan-1","tags":["plan-1"],"id":"607d1ae3f0","short_id":"607d1ae3"},
                     {"time":"2026-09-12T10:13:47Z","paths":["/daten"],"hostname":"plan-1",
                      "id":"aaa111bbb2","short_id":"aaa111bb"}]""");

            assertThat(snapshots).hasSize(2);
            assertThat(snapshots.getFirst().id()).isEqualTo("607d1ae3f0");
            assertThat(snapshots.getFirst().hostname()).isEqualTo("plan-1");
            assertThat(snapshots.getFirst().tags()).containsExactly("plan-1");
            assertThat(snapshots.getFirst().paths()).containsExactly("/daten");
            assertThat(snapshots.getFirst().time())
                    .isEqualTo(Instant.parse("2026-09-13T10:13:47.814652Z"));
        }

        @Test
        @DisplayName("Kommt auch mit einer Zeile je Snapshot zurecht")
        void readsLineByLine() {
            // Je nach Kommando und Version gibt restic beides aus.
            var snapshots = parser.parseSnapshots("""
                    {"id":"aaa","time":"2026-09-13T10:00:00Z"}
                    {"id":"bbb","time":"2026-09-13T11:00:00Z"}""");

            assertThat(snapshots).extracting(ResticListing.Snapshot::id).containsExactly("aaa", "bbb");
        }

        @Test
        @DisplayName("Eine leere Ausgabe ist kein Fehler")
        void emptyOutputIsNoError() {
            // Ein frisch angelegtes Repository hat noch keinen Snapshot.
            assertThat(parser.parseSnapshots("[]")).isEmpty();
            assertThat(parser.parseSnapshots("")).isEmpty();
            assertThat(parser.parseSnapshots(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Eintraege")
    class Nodes {

        // Genau wie restic es ausgibt: ein JSON-Objekt je Zeile, ohne Umbrueche.
        private static final String LISTING = """
                {"time":"2026-09-13T10:13:47Z","paths":["/daten"],"hostname":"plan-1","id":"607d1ae3f0","struct_type":"snapshot"}
                {"name":"daten","type":"dir","path":"/daten","uid":0,"gid":0,"mtime":"2026-09-13T09:00:00Z","struct_type":"node"}
                {"name":"wichtig.txt","type":"file","path":"/daten/wichtig.txt","size":42,"mtime":"2026-09-13T09:30:00Z","struct_type":"node"}
                {"name":"unterordner","type":"dir","path":"/daten/unterordner","mtime":"2026-09-13T09:31:00Z","struct_type":"node"}""";

        @Test
        @DisplayName("Trennt Dateien von Verzeichnissen")
        void separatesFilesFromDirectories() {
            // Davon haengt ab, wo man hineingehen kann und wo nicht.
            var nodes = parser.parseNodes(LISTING);

            assertThat(nodes).hasSize(3);
            assertThat(nodes).filteredOn(ResticListing.Node::directory)
                    .extracting(ResticListing.Node::name)
                    .containsExactly("daten", "unterordner");
            assertThat(nodes.get(1).size()).isEqualTo(42);
        }

        @Test
        @DisplayName("Uebergeht die Kopfzeile des Snapshots")
        void skipsTheSnapshotHeader() {
            // Sie hat keinen Pfad und waere im Baum ein Eintrag ohne Bedeutung.
            assertThat(parser.parseNodes(LISTING))
                    .noneSatisfy(node -> assertThat(node.name()).isEqualTo("607d1ae3f0"));
        }

        @Test
        @DisplayName("Die Ebene ergibt sich aus dem Pfad")
        void depthComesFromThePath() {
            // Darauf beruht das Blaettern: Nur die unmittelbaren Kinder werden gezeigt.
            var nodes = parser.parseNodes(LISTING);

            assertThat(nodes.getFirst().depth()).isEqualTo(1);
            assertThat(nodes.get(1).depth()).isEqualTo(2);
        }

        @Test
        @DisplayName("Unlesbare Zeilen werden uebergangen, nicht zum Fehler erklaert")
        void skipsBrokenLines() {
            // Sonst scheitert das Blaettern an einer Warnung, die restic nebenbei ausgibt.
            var nodes = parser.parseNodes("""
                    Fatal: some warning that is not JSON at all
                    {"name":"a.txt","type":"file","path":"/a.txt"}
                    {kaputt
                    {"name":"b.txt","type":"file","path":"/b.txt"}""");

            assertThat(nodes).extracting(ResticListing.Node::name).containsExactly("a.txt", "b.txt");
        }
    }
}
