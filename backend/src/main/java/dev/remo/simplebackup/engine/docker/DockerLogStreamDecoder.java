package dev.remo.simplebackup.engine.docker;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Zerlegt den Logstrom der Docker-API in einzelne Zeilen.
 *
 * <p>Ohne TTY liefert Docker die Ausgabe nicht roh, sondern in Rahmen mit einem Kopf von
 * acht Byte:
 *
 * <pre>
 *   Byte 0     Stromart: 1 = stdout, 2 = stderr
 *   Byte 1..3  ungenutzt
 *   Byte 4..7  Laenge der Nutzdaten, Big Endian
 *   Byte 8..n  Nutzdaten
 * </pre>
 *
 * <p>Wer das uebersieht, bekommt Steuerzeichen mitten in den Logzeilen -- ein klassischer
 * Stolperstein bei der Docker-API.
 *
 * <p>Ein TTY zu verlangen waere die bequemere, aber falsche Abkuerzung: Werkzeuge verhalten
 * sich an einem TTY anders, geben Fortschrittsbalken mit Wagenruecklauf statt Zeilenumbruch
 * aus und faerben ihre Ausgabe ein. Fuer ein Backup-Protokoll ist das Verhalten ohne TTY
 * das richtige.
 *
 * <p>Nutzdaten koennen mitten in einer Zeile enden; unvollstaendige Zeilen werden deshalb
 * ueber Rahmengrenzen hinweg gesammelt.
 */
public final class DockerLogStreamDecoder {

    private static final int HEADER_LENGTH = 8;
    /** Schutz gegen eine einzelne endlose Zeile ohne Zeilenumbruch. */
    private static final int MAX_LINE_LENGTH = 64 * 1024;

    private DockerLogStreamDecoder() {
    }

    /**
     * Liest den Strom bis zum Ende und meldet jede vollstaendige Zeile.
     *
     * @param stream       Antwortkoerper von {@code GET /containers/{id}/logs}
     * @param lineConsumer erhaelt jede Zeile ohne Zeilenumbruch
     */
    public static void decode(InputStream stream, Consumer<String> lineConsumer) throws IOException {
        StringBuilder pending = new StringBuilder();
        byte[] header = new byte[HEADER_LENGTH];

        while (true) {
            if (!readFully(stream, header, HEADER_LENGTH)) {
                break;
            }

            int payloadLength = ((header[4] & 0xFF) << 24)
                    | ((header[5] & 0xFF) << 16)
                    | ((header[6] & 0xFF) << 8)
                    | (header[7] & 0xFF);

            if (payloadLength < 0) {
                throw new IOException("Ungueltige Rahmenlaenge im Docker-Logstrom: " + payloadLength);
            }
            if (payloadLength == 0) {
                continue;
            }

            byte[] payload = new byte[payloadLength];
            if (!readFully(stream, payload, payloadLength)) {
                throw new EOFException("Docker-Logstrom endet vor den Nutzdaten eines Rahmens");
            }

            emitLines(new String(payload, StandardCharsets.UTF_8), pending, lineConsumer);
        }

        // Eine letzte Zeile ohne abschliessenden Umbruch geht sonst verloren -- und das ist
        // bei einem Fehlerfall oft genau die aussagekraeftige.
        if (!pending.isEmpty()) {
            lineConsumer.accept(pending.toString());
        }
    }

    private static void emitLines(String chunk, StringBuilder pending, Consumer<String> lineConsumer) {
        for (int i = 0; i < chunk.length(); i++) {
            char character = chunk.charAt(i);

            if (character == '\n') {
                lineConsumer.accept(stripTrailingReturn(pending.toString()));
                pending.setLength(0);
            } else if (pending.length() >= MAX_LINE_LENGTH) {
                // Ein Werkzeug ohne Zeilenumbrueche darf den Speicher nicht fuellen.
                lineConsumer.accept(pending.toString());
                pending.setLength(0);
                pending.append(character);
            } else {
                pending.append(character);
            }
        }
    }

    /** Werkzeuge, die Fortschritt anzeigen, beenden Zeilen mit Wagenruecklauf. */
    private static String stripTrailingReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * Liest genau {@code length} Byte.
     *
     * @return false, wenn der Strom sauber an einer Rahmengrenze endet
     * @throws EOFException wenn er mitten in einem Rahmen abbricht -- das ist ein Fehler und
     *                      darf nicht als regulaeres Ende durchgehen
     */
    private static boolean readFully(InputStream stream, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = stream.read(buffer, read, length - read);
            if (count < 0) {
                if (read == 0) {
                    return false;
                }
                throw new EOFException(
                        "Docker-Logstrom endet nach %d von %d erwarteten Byte".formatted(read, length));
            }
            read += count;
        }
        return true;
    }
}
