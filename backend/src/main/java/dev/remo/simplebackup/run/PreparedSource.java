package dev.remo.simplebackup.run;

import dev.remo.simplebackup.engine.VolumeMount;
import java.util.List;

/**
 * Was gesichert werden soll, nachdem die Quelle es bereitgestellt hat.
 *
 * <p>Der Kern der zweistufigen Pipeline: Erst beschafft ein Produzent die Daten -- ein Dump,
 * ein Klon, ein Verzeichnis --, dann sichert die Speicher-Engine sie. Beides getrennt zu
 * halten heisst, dass jede neue Quelle nur noch eine Argumentliste ist und nichts ueber
 * restic wissen muss.
 *
 * @param paths    zu sichernde Pfade, aus Sicht des Runners
 * @param mounts   Einhaengungen, die der Sicherungsschritt dafuer braucht
 * @param excludes Ausschlussmuster
 * @param stagingDirectory Verzeichnis mit Zwischenstaenden, das hinterher weggeraeumt wird,
 *                         oder {@code null}, wenn nichts erzeugt wurde
 */
record PreparedSource(
        List<String> paths,
        List<VolumeMount> mounts,
        List<String> excludes,
        String stagingDirectory) {

    PreparedSource {
        paths = List.copyOf(paths);
        mounts = List.copyOf(mounts);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    /** Ohne eigenen Beschaffungsschritt: Die Daten liegen schon da, wo sie liegen. */
    static PreparedSource directly(List<String> paths, List<VolumeMount> mounts, List<String> excludes) {
        return new PreparedSource(paths, mounts, excludes, null);
    }
}
