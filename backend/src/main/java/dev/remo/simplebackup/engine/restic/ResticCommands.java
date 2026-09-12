package dev.remo.simplebackup.engine.restic;

import dev.remo.simplebackup.shared.RetentionRule;
import java.util.ArrayList;
import java.util.List;

/**
 * Baut die Argumentlisten fuer restic.
 *
 * <p>Ausschliesslich Listen, nie ein Kommandostring: Ein Pfad mit Leerzeichen, Anfuehrungs-
 * oder Sonderzeichen bleibt damit ein Argument.
 *
 * <p><b>Zur Bedeutung von {@code --host} und {@code --tag}:</b> restic schreibt den Hostnamen
 * in jeden Snapshot. Im Container waere das eine bei jedem Lauf neue Zufallskennung, womit
 * die Snapshots eines Plans nicht mehr als zusammengehoerig erkennbar waeren. Beide Angaben
 * werden deshalb fest gesetzt -- und sie begrenzen zugleich {@code forget}: Ohne sie wuerde
 * die Aufbewahrungsregel eines Plans die Snapshots aller anderen Plaene im selben Repository
 * mitloeschen.
 */
public final class ResticCommands {

    private static final String RESTIC = "restic";

    private ResticCommands() {
    }

    /**
     * Legt ein Repository an. Schlaegt fehl, wenn bereits eines existiert -- das ist
     * gewollt, denn ein zweites Anlegen wuerde ein vorhandenes unbrauchbar machen.
     */
    public static List<String> init() {
        return List.of(RESTIC, "init", "--json");
    }

    /**
     * @param paths       zu sichernde Pfade aus Sicht des Runners
     * @param host        stabile Kennung des Plans, siehe Klassenkommentar
     * @param tag         Kennzeichnung des Plans
     * @param excludes    Ausschlussmuster
     * @param excludeFile Pfad zu einer Datei mit Ausschluessen, oder {@code null}
     * @param oneFileSystem ob an Dateisystemgrenzen haltgemacht wird. Sinnvoll, damit ein
     *                      eingehaengtes Netzlaufwerk nicht unbemerkt mitgesichert wird.
     */
    public static List<String> backup(List<String> paths, String host, String tag,
            List<String> excludes, String excludeFile, boolean oneFileSystem) {

        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("Ohne Pfade gibt es nichts zu sichern");
        }
        requireText(host, "host");

        var command = new ArrayList<>(List.of(RESTIC, "backup", "--json"));
        command.add("--host");
        command.add(host);

        if (hasText(tag)) {
            command.add("--tag");
            command.add(tag);
        }
        if (oneFileSystem) {
            command.add("--one-file-system");
        }
        if (excludes != null) {
            for (String pattern : excludes) {
                if (hasText(pattern)) {
                    command.add("--exclude");
                    command.add(pattern);
                }
            }
        }
        if (hasText(excludeFile)) {
            command.add("--exclude-file");
            command.add(excludeFile);
        }

        // Die Pfade zuletzt. Das doppelte Minus trennt sie von den Schaltern, sodass auch
        // ein Pfad, der mit einem Bindestrich beginnt, als Pfad gilt.
        command.add("--");
        command.addAll(paths);
        return List.copyOf(command);
    }

    /** Listet Snapshots. Ohne Filter alle, mit Filter nur die des jeweiligen Plans. */
    public static List<String> snapshots(String host, String tag) {
        var command = new ArrayList<>(List.of(RESTIC, "snapshots", "--json"));
        addFilters(command, host, tag);
        return List.copyOf(command);
    }

    /**
     * Wendet die Aufbewahrungsregel an.
     *
     * <p>{@code host} und {@code tag} sind Pflicht: Ohne sie wuerde die Regel eines Plans
     * auch die Snapshots aller anderen Plaene im selben Repository loeschen.
     *
     * @param prune ob der freigewordene Platz sofort zurueckgewonnen wird. Getrennt vom
     *              Backup ausfuehren -- ein Backup, das am Prune scheitert, hat trotzdem
     *              gesichert.
     * @param dryRun nur anzeigen, was geloescht wuerde
     */
    public static List<String> forget(RetentionRule rule, String host, String tag,
            boolean prune, boolean dryRun) {

        requireText(host, "host");
        requireText(tag, "tag");
        if (rule == null) {
            throw new IllegalArgumentException("Ohne Aufbewahrungsregel wird nichts geloescht");
        }

        var command = new ArrayList<>(List.of(RESTIC, "forget", "--json"));
        addFilters(command, host, tag);

        addKeep(command, "--keep-last", rule.keepLast());
        addKeep(command, "--keep-hourly", rule.keepHourly());
        addKeep(command, "--keep-daily", rule.keepDaily());
        addKeep(command, "--keep-weekly", rule.keepWeekly());
        addKeep(command, "--keep-monthly", rule.keepMonthly());
        addKeep(command, "--keep-yearly", rule.keepYearly());

        if (rule.keepWithinDays() != null && rule.keepWithinDays() > 0) {
            command.add("--keep-within");
            command.add(rule.keepWithinDays() + "d");
        }
        if (prune) {
            command.add("--prune");
        }
        if (dryRun) {
            command.add("--dry-run");
        }
        return List.copyOf(command);
    }

    /**
     * Prueft die Unversehrtheit.
     *
     * @param readDataSubsetPercent Anteil der Daten, der tatsaechlich gelesen wird.
     *                              {@code null} prueft nur die Struktur -- schnell, aber es
     *                              bliebe unbemerkt, wenn Daten am Ziel verrottet sind.
     */
    public static List<String> check(Integer readDataSubsetPercent) {
        var command = new ArrayList<>(List.of(RESTIC, "check", "--json"));

        if (readDataSubsetPercent != null) {
            if (readDataSubsetPercent < 1 || readDataSubsetPercent > 100) {
                throw new IllegalArgumentException(
                        "Der Anteil muss zwischen 1 und 100 liegen: " + readDataSubsetPercent);
            }
            command.add("--read-data-subset=" + readDataSubsetPercent + "%");
        }
        return List.copyOf(command);
    }

    /**
     * Stellt einen Snapshot wieder her.
     *
     * @param snapshotId Kennung, oder {@code latest}
     * @param targetPath Zielverzeichnis aus Sicht des Runners
     * @param includes   nur diese Pfade, oder leer fuer alles
     */
    public static List<String> restore(String snapshotId, String targetPath, List<String> includes) {
        requireText(snapshotId, "snapshotId");
        requireText(targetPath, "targetPath");
        if (!targetPath.startsWith("/")) {
            throw new IllegalArgumentException("Das Zielverzeichnis muss absolut sein: " + targetPath);
        }

        var command = new ArrayList<>(List.of(RESTIC, "restore", "--json", snapshotId,
                "--target", targetPath));

        if (includes != null) {
            for (String include : includes) {
                if (hasText(include)) {
                    command.add("--include");
                    command.add(include);
                }
            }
        }
        return List.copyOf(command);
    }

    /** Listet den Inhalt eines Snapshots. Grundlage des Snapshot-Browsers. */
    public static List<String> list(String snapshotId, String path) {
        requireText(snapshotId, "snapshotId");
        var command = new ArrayList<>(List.of(RESTIC, "ls", "--json", snapshotId));
        if (hasText(path)) {
            command.add(path);
        }
        return List.copyOf(command);
    }

    /** Gibt eine einzelne Datei aus, ohne den ganzen Snapshot auszupacken. */
    public static List<String> dump(String snapshotId, String path) {
        requireText(snapshotId, "snapshotId");
        requireText(path, "path");
        return List.of(RESTIC, "dump", snapshotId, path);
    }

    /**
     * Loest eine Sperre.
     *
     * <p>Noetig, wenn ein Lauf abgebrochen wurde, bevor restic aufraeumen konnte -- etwa
     * nach einem Zeitlimit oder einem Stromausfall.
     */
    public static List<String> unlock() {
        return List.of(RESTIC, "unlock");
    }

    private static void addFilters(List<String> command, String host, String tag) {
        if (hasText(host)) {
            command.add("--host");
            command.add(host);
        }
        if (hasText(tag)) {
            command.add("--tag");
            command.add(tag);
        }
    }

    private static void addKeep(List<String> command, String option, Integer value) {
        if (value != null && value > 0) {
            command.add(option);
            command.add(String.valueOf(value));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
    }
}
