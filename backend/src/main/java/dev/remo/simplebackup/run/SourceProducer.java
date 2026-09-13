package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceType;

/**
 * Stellt die Daten einer Quelle bereit, damit sie gesichert werden koennen.
 *
 * <p>Fuer ein Verzeichnis ist das nichts weiter als der Pfad. Fuer eine Datenbank ist es ein
 * Dump, fuer GitHub ein Klon. Erst diese Trennung macht die Quellmatrix beherrschbar: Die
 * Sicherung selbst bleibt in allen Faellen dieselbe.
 */
interface SourceProducer {

    SourceType type();

    /**
     * Beschafft die Daten.
     *
     * <p>Meldet eigene Schritte ueber den Listener, damit in der Historie steht, woran ein
     * Lauf gescheitert ist -- am Dump oder erst an der Uebertragung.
     *
     * @param stagingDirectory Verzeichnis fuer Zwischenstaende, aus Sicht des Backends
     * @throws IllegalStateException wenn die Beschaffung scheitert
     */
    PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener);
}
