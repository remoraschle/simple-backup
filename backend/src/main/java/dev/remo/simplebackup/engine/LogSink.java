package dev.remo.simplebackup.engine;

/**
 * Nimmt die Ausgabe eines laufenden Schritts zeilenweise entgegen.
 *
 * <p>Zeilenweise und nicht als Ganzes, weil die Ausgabe waehrend des Laufs gebraucht wird:
 * Sie wird in die Logdatei geschrieben und zugleich per Server-Sent Events an die Oberflaeche
 * gestreamt. Eine Ausgabe, die erst am Ende erscheint, hilft bei einem vierstuendigen Lauf
 * niemandem.
 *
 * <p>Implementierungen muessen damit rechnen, aus einem anderen Thread aufgerufen zu werden,
 * und duerfen nicht blockieren.
 */
@FunctionalInterface
public interface LogSink {

    /** @param line eine Ausgabezeile ohne Zeilenumbruch, bereits von Geheimnissen bereinigt */
    void accept(String line);

    /** Verwirft alles. Fuer Faelle, in denen die Ausgabe nicht gebraucht wird. */
    static LogSink discarding() {
        return line -> { };
    }
}
