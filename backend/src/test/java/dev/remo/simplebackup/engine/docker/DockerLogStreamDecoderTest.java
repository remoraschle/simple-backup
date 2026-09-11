package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Der Logstrom der Docker-API ist gerahmt, nicht roh. Wer das uebersieht, bekommt
 * Steuerzeichen mitten in den Logzeilen -- deshalb hier dicht getestet, inklusive der Faelle,
 * in denen Rahmengrenzen und Zeilengrenzen nicht zusammenfallen.
 */
class DockerLogStreamDecoderTest {

    private static final byte STDOUT = 1;
    private static final byte STDERR = 2;

    private final List<String> lines = new ArrayList<>();

    private void decode(byte[] stream) throws IOException {
        DockerLogStreamDecoder.decode(new ByteArrayInputStream(stream), lines::add);
    }

    /** Baut einen Rahmen, wie ihn die Docker-API sendet. */
    private static byte[] frame(byte streamType, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream();
        out.write(streamType);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write((data.length >> 24) & 0xFF);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("Der Rahmenkopf landet nicht in der Logzeile")
    void stripsFrameHeader() throws IOException {
        decode(frame(STDOUT, "scan finished in 1.2s\n"));

        assertThat(lines).containsExactly("scan finished in 1.2s");
    }

    @Test
    @DisplayName("Mehrere Zeilen in einem Rahmen werden getrennt")
    void splitsMultipleLinesInOneFrame() throws IOException {
        decode(frame(STDOUT, "erste\nzweite\ndritte\n"));

        assertThat(lines).containsExactly("erste", "zweite", "dritte");
    }

    @Test
    @DisplayName("Eine Zeile, die ueber zwei Rahmen verteilt ist, wird zusammengesetzt")
    void joinsLineAcrossFrames() throws IOException {
        // Der wichtigste Fall: Rahmengrenzen fallen nicht mit Zeilengrenzen zusammen.
        decode(concat(frame(STDOUT, "processed 1234 fi"), frame(STDOUT, "les, 5.6 GiB\n")));

        assertThat(lines).containsExactly("processed 1234 files, 5.6 GiB");
    }

    @Test
    @DisplayName("stdout und stderr landen gemeinsam in der Reihenfolge ihres Eintreffens")
    void mergesBothStreamsInOrder() throws IOException {
        // Viele Werkzeuge melden Fortschritt auf dem einen und Fehler auf dem anderen Strom,
        // ohne sich an eine Regel zu halten. Fuer das Protokoll zaehlt die Reihenfolge.
        decode(concat(
                frame(STDOUT, "starte\n"),
                frame(STDERR, "Warnung: Datei uebersprungen\n"),
                frame(STDOUT, "fertig\n")));

        assertThat(lines).containsExactly("starte", "Warnung: Datei uebersprungen", "fertig");
    }

    @Test
    @DisplayName("Eine letzte Zeile ohne Zeilenumbruch geht nicht verloren")
    void emitsTrailingLineWithoutNewline() throws IOException {
        // Bei einem Abbruch ist die letzte, unvollstaendige Zeile oft die aussagekraeftige.
        decode(frame(STDERR, "Fatal: repository is locked"));

        assertThat(lines).containsExactly("Fatal: repository is locked");
    }

    @Test
    @DisplayName("Ein Wagenruecklauf am Zeilenende wird entfernt")
    void stripsCarriageReturn() throws IOException {
        // Werkzeuge mit Fortschrittsanzeige beenden Zeilen so.
        decode(frame(STDOUT, "50% erledigt\r\n"));

        assertThat(lines).containsExactly("50% erledigt");
    }

    @Test
    @DisplayName("Umlaute ueberleben die Dekodierung")
    void handlesUtf8() throws IOException {
        decode(frame(STDOUT, "Verzeichnis /srv/büro/größe übersprungen\n"));

        assertThat(lines).containsExactly("Verzeichnis /srv/büro/größe übersprungen");
    }

    @Test
    @DisplayName("Leere Rahmen werden uebergangen")
    void ignoresEmptyFrames() throws IOException {
        decode(concat(frame(STDOUT, ""), frame(STDOUT, "inhalt\n"), frame(STDOUT, "")));

        assertThat(lines).containsExactly("inhalt");
    }

    @Test
    @DisplayName("Ein leerer Strom liefert keine Zeilen")
    void handlesEmptyStream() throws IOException {
        decode(new byte[0]);

        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("Ein abgeschnittener Rahmen wird als Fehler gemeldet, nicht stillschweigend geschluckt")
    void failsOnTruncatedFrame() {
        // Ein abgebrochener Strom darf nicht wie ein sauberes Ende aussehen -- sonst gaelte
        // ein unterbrochener Lauf als vollstaendig protokolliert.
        byte[] truncated = concat(frame(STDOUT, "vollstaendig\n"), new byte[] {1, 0, 0, 0, 0, 0, 0});

        assertThatThrownBy(() -> decode(truncated))
                .isInstanceOf(EOFException.class)
                .hasMessageContaining("endet nach");
    }

    @Test
    @DisplayName("Ein Rahmen, dessen Nutzdaten fehlen, wird als Fehler gemeldet")
    void failsOnMissingPayload() {
        byte[] header = new byte[] {1, 0, 0, 0, 0, 0, 0, 100};

        assertThatThrownBy(() -> decode(header)).isInstanceOf(EOFException.class);
    }

    @Test
    @DisplayName("Eine sehr lange Zeile ohne Umbruch fuellt nicht den Speicher")
    void limitsLineLength() throws IOException {
        // Ein Werkzeug, das nie einen Zeilenumbruch schickt, darf das Backend nicht
        // aushungern.
        decode(frame(STDOUT, "x".repeat(200_000)));

        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(64 * 1024 + 1));
    }
}
