package dev.remo.simplebackup.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schreibt die Ausgabe eines Laufs in eine Datei.
 *
 * <p>In die Datei und nicht in die Datenbank: Ein einziger Lauf ueber viele Dateien erzeugt
 * Zehntausende Zeilen. In der Datenbank stehen nur der Pfad und die Kurzfassung des Fehlers.
 *
 * <p>Ein Fehler beim Schreiben bricht den Lauf nicht ab -- ein Backup, das wegen eines vollen
 * Logverzeichnisses scheitert, waere das falsche Verhalten.
 */
public class RunLogWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunLogWriter.class);

    private final Path file;
    private final Object lock = new Object();
    private volatile boolean broken;

    RunLogWriter(Path directory, UUID runId) {
        this.file = directory.resolve(runId + ".log");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            broken = true;
            log.warn("Logdatei {} nicht anlegbar: {}", file, e.getMessage());
        }
    }

    /** Der Pfad, wie er in der Datenbank vermerkt wird. */
    public String path() {
        return file.toString();
    }

    /**
     * Haengt eine Zeile an.
     *
     * <p>Synchronisiert, weil mehrere Schritte eines Laufs gleichzeitig schreiben koennen.
     */
    public void append(String line) {
        if (broken) {
            return;
        }
        synchronized (lock) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                broken = true;
                log.warn("Logdatei {} nicht mehr beschreibbar: {}", file, e.getMessage());
            }
        }
    }

    /** Eine Ueberschrift, die die Schritte im Protokoll voneinander trennt. */
    public void appendSection(String title) {
        append("");
        append("─── %s ── %s".formatted(title, Instant.now()));
    }

    @Override
    public void close() {
        // Nichts offen zu halten: Es wird je Zeile geschrieben, damit die Ausgabe auch dann
        // vollstaendig ist, wenn das Backend mitten im Lauf abstuerzt.
    }

    /** Liest die Datei zurueck, etwa fuer die Anzeige eines abgeschlossenen Laufs. */
    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
