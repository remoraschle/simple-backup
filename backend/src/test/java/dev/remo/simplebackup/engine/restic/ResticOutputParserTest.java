package dev.remo.simplebackup.engine.restic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Beispielzeilen entsprechen dem Format, das restic mit {@code --json} ausgibt.
 */
class ResticOutputParserTest {

    private final ResticOutputParser parser = new ResticOutputParser(new ObjectMapper());

    @Nested
    @DisplayName("Fortschritt")
    class Progress {

        @Test
        @DisplayName("Eine Statusmeldung liefert Anteil, Dateien und Bytes")
        void readsStatusMessage() {
            var message = parser.parse("""
                    {"message_type":"status","seconds_elapsed":12,"seconds_remaining":48,\
                    "percent_done":0.25,"total_files":1200,"files_done":300,\
                    "total_bytes":5368709120,"bytes_done":1342177280,"current_files":["/srv/fotos/a.jpg"]}""")
                    .orElseThrow();

            assertThat(message).isInstanceOf(ResticMessage.Progress.class);
            var progress = (ResticMessage.Progress) message;

            assertThat(progress.percent()).isEqualTo(25);
            assertThat(progress.filesDone()).isEqualTo(300);
            assertThat(progress.totalFiles()).isEqualTo(1200);
            assertThat(progress.bytesDone()).isEqualTo(1_342_177_280L);
            assertThat(progress.secondsRemaining()).isEqualTo(48);
        }

        @Test
        @DisplayName("Fehlende Felder machen die Meldung nicht unbrauchbar")
        void toleratesMissingFields() {
            // Zu Beginn eines Laufs kennt restic weder Gesamtzahl noch Restzeit.
            var progress = (ResticMessage.Progress) parser.parse("""
                    {"message_type":"status","percent_done":0}""").orElseThrow();

            assertThat(progress.percent()).isZero();
            assertThat(progress.totalFiles()).isNull();
            assertThat(progress.secondsRemaining()).isNull();
        }

        @Test
        @DisplayName("Der Anteil bleibt zwischen 0 und 100")
        void clampsPercentage() {
            // Bei wachsenden Quellen meldet restic gelegentlich mehr als 100 Prozent.
            var progress = (ResticMessage.Progress) parser.parse("""
                    {"message_type":"status","percent_done":1.4}""").orElseThrow();

            assertThat(progress.percent()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Abschluss")
    class Summary {

        @Test
        @DisplayName("Die Abschlussmeldung liefert Kennzahlen und die Snapshot-Kennung")
        void readsSummary() {
            var summary = (ResticMessage.Summary) parser.parse("""
                    {"message_type":"summary","files_new":42,"files_changed":7,"files_unmodified":1151,\
                    "dirs_new":3,"dirs_changed":1,"dirs_unmodified":88,"data_blobs":120,"tree_blobs":9,\
                    "data_added":268435456,"total_files_processed":1200,"total_bytes_processed":5368709120,\
                    "total_duration":63.5,"snapshot_id":"4bba301e1cbd4ff7a5f1e3b8f0c2a9d7"}""")
                    .orElseThrow();

            assertThat(summary.filesNew()).isEqualTo(42);
            assertThat(summary.filesChanged()).isEqualTo(7);
            assertThat(summary.filesUnmodified()).isEqualTo(1151);
            // Nach Deduplizierung tatsaechlich uebertragen -- die aussagekraeftige Zahl.
            assertThat(summary.dataAdded()).isEqualTo(268_435_456L);
            assertThat(summary.totalBytesProcessed()).isEqualTo(5_368_709_120L);
            assertThat(summary.totalDurationSeconds()).isEqualTo(63.5);
            assertThat(summary.snapshotId()).isEqualTo("4bba301e1cbd4ff7a5f1e3b8f0c2a9d7");
        }
    }

    @Nested
    @DisplayName("Fehler")
    class Failures {

        @Test
        @DisplayName("Ein Fehler mit Unterobjekt wird gelesen")
        void readsNestedError() {
            var failure = (ResticMessage.Failure) parser.parse("""
                    {"message_type":"error","error":{"message":"open /srv/geheim: permission denied"},\
                    "during":"archival","item":"/srv/geheim"}""").orElseThrow();

            assertThat(failure.message()).isEqualTo("open /srv/geheim: permission denied");
            assertThat(failure.during()).isEqualTo("archival");
            assertThat(failure.item()).isEqualTo("/srv/geheim");
        }

        @Test
        @DisplayName("Ein Fehler als reine Zeichenkette wird ebenfalls gelesen")
        void readsFlatError() {
            // Aeltere Versionen liefern das Feld unmittelbar statt als Unterobjekt.
            var failure = (ResticMessage.Failure) parser.parse("""
                    {"message_type":"error","error":"Fatal: unable to open config file"}""").orElseThrow();

            assertThat(failure.message()).isEqualTo("Fatal: unable to open config file");
        }

        @Test
        @DisplayName("Ein Fehler ohne Text bleibt auswertbar")
        void handlesErrorWithoutMessage() {
            var failure = (ResticMessage.Failure) parser.parse("""
                    {"message_type":"error"}""").orElseThrow();

            assertThat(failure.message()).isEqualTo("Unbekannter Fehler");
        }
    }

    @Nested
    @DisplayName("Nachsicht")
    class Leniency {

        @Test
        @DisplayName("Unbekannte Meldungsarten werden uebergangen")
        void ignoresUnknownMessageTypes() {
            // restic ergaenzt Meldungsarten zwischen Versionen. Ein Backup daran scheitern
            // zu lassen, waere die falsche Abwaegung.
            assertThat(parser.parse("""
                    {"message_type":"verbose_status","action":"unchanged","item":"/a"}""")).isEmpty();
        }

        @Test
        @DisplayName("Zeilen ohne JSON werden uebergangen")
        void ignoresPlainTextLines() {
            // restic mischt Warnungen im Klartext unter die JSON-Zeilen.
            assertThat(parser.parse("warning: could not read directory")).isEmpty();
            assertThat(parser.parse("")).isEmpty();
            assertThat(parser.parse("   ")).isEmpty();
            assertThat(parser.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("Abgeschnittenes JSON wird uebergangen statt zu scheitern")
        void ignoresTruncatedJson() {
            // Kommt vor, wenn zwei Ausgaben ineinandergeraten.
            assertThat(parser.parse("""
                    {"message_type":"status","percent_done":0.5""")).isEmpty();
        }

        @Test
        @DisplayName("JSON ohne Meldungsart wird uebergangen")
        void ignoresJsonWithoutMessageType() {
            assertThat(parser.parse("""
                    {"irgendwas":"anderes"}""")).isEmpty();
        }
    }
}
