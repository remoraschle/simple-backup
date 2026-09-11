package dev.remo.simplebackup.engine;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Uebersetzt Pfade aus der Sicht des Backends in Pfade aus der Sicht des Docker-Daemons.
 *
 * <p>Der Knackpunkt des Runner-Modells: Das Backend sieht {@code /sources/fotos}, der
 * Docker-Daemon loest Bind-Mounts aber gegen das <em>Host</em>-Dateisystem auf und braucht
 * deshalb {@code /srv/fotos}. Die Zuordnung stammt aus der eigenen Mount-Tabelle des
 * Backend-Containers -- kein zweiter, von Hand gepflegter Pfad-Katalog, der beim naechsten
 * Umbau der Compose-Datei still falsch wuerde.
 *
 * <p>Was sich nicht aufloesen laesst, wird abgelehnt statt geraten.
 */
public class MountTranslator {

    /** Mounts des Backend-Containers, absteigend nach Laenge des Zielpfads. */
    private final List<VolumeMount> ownMounts;

    public MountTranslator(List<VolumeMount> ownMounts) {
        // Der spezifischste Mount gewinnt: Sind sowohl /mnt als auch /mnt/nas eingehaengt,
        // gehoert /mnt/nas/fotos zu /mnt/nas.
        this.ownMounts = ownMounts.stream()
                .sorted(Comparator.comparingInt((VolumeMount mount) -> mount.target().length()).reversed())
                .toList();
    }

    /**
     * Baut die Einhaengung, mit der ein Runner den gewuenschten Pfad sieht.
     *
     * <p>Bei einem Verzeichnis-Mount wird gezielt der Unterpfad eingehaengt und nicht der
     * ganze Mount: Der Runner soll nur sehen, was er braucht.
     *
     * <p>Bei einem benannten Volume wird immer das ganze Volume eingehaengt -- Docker kann
     * kein Unterverzeichnis eines Volumes einhaengen. Der gewuenschte Unterpfad ist darin
     * dann trotzdem erreichbar.
     *
     * @param containerPath Pfad aus Sicht des Backends
     * @param readOnly      true fuer Quellen
     * @throws PathTranslationException wenn der Pfad keinem Mount zugeordnet werden kann
     */
    public VolumeMount translate(String containerPath, boolean readOnly) {
        String normalized = normalize(containerPath);

        VolumeMount mount = findMountFor(normalized).orElseThrow(() -> new PathTranslationException("""
                Der Pfad %s ist im Backend-Container nicht eingehaengt und kann deshalb keinem \
                Host-Pfad zugeordnet werden.

                Ein Runner koennte ihn nicht erreichen. Trage das Verzeichnis in der \
                Compose-Datei unter dem Dienst "backend" ein -- Quellen mit :ro.

                Derzeit eingehaengt: %s"""
                .formatted(normalized, describeAvailableMounts())));

        if (mount.namedVolume()) {
            return new VolumeMount(mount.source(), mount.target(), readOnly, true);
        }

        String relative = normalized.substring(mount.target().length());
        return new VolumeMount(mount.source() + relative, normalized, readOnly, false);
    }

    /** Ob der Pfad aufloesbar ist. Fuer die Pruefung beim Anlegen einer Quelle. */
    public boolean canTranslate(String containerPath) {
        try {
            return findMountFor(normalize(containerPath)).isPresent();
        } catch (PathTranslationException e) {
            return false;
        }
    }

    private Optional<VolumeMount> findMountFor(String normalizedPath) {
        return ownMounts.stream().filter(mount -> isWithin(normalizedPath, mount.target())).findFirst();
    }

    /**
     * Ob der Pfad im Mount liegt.
     *
     * <p>Die Pruefung auf die Pfadgrenze ist wesentlich: {@code /sourcesXYZ} liegt nicht in
     * {@code /sources}, auch wenn der Zeichenkettenvergleich das nahelegt.
     */
    private static boolean isWithin(String path, String mountTarget) {
        if (path.equals(mountTarget)) {
            return true;
        }
        String prefix = mountTarget.endsWith("/") ? mountTarget : mountTarget + "/";
        return path.startsWith(prefix);
    }

    /**
     * Normalisiert den Pfad und weist alles zurueck, was ausbrechen koennte.
     *
     * <p>Ohne diese Pruefung liesse sich mit {@code /sources/fotos/../../etc/shadow} ein
     * Verzeichnis sichern -- oder ueberschreiben -- das nie eingehaengt wurde.
     */
    private static String normalize(String containerPath) {
        if (containerPath == null || containerPath.isBlank()) {
            throw new PathTranslationException("Pfad darf nicht leer sein");
        }
        if (!containerPath.startsWith("/")) {
            throw new PathTranslationException("Pfad muss absolut sein: " + containerPath);
        }
        if (containerPath.contains("\0")) {
            throw new PathTranslationException("Pfad enthaelt ein Nullbyte");
        }

        String normalized = Path.of(containerPath).normalize().toString();

        // Path.normalize() entfernt "..", solange es nicht ueber die Wurzel hinausgeht.
        // Ein Pfad, der sich dabei veraendert hat, war ein Ausbruchsversuch oder zumindest
        // missverstaendlich -- in beiden Faellen ist Ablehnen richtig.
        if (containerPath.contains("..")) {
            throw new PathTranslationException(
                    "Pfad enthaelt \"..\" und wird nicht aufgeloest: " + containerPath);
        }
        return normalized;
    }

    private String describeAvailableMounts() {
        if (ownMounts.isEmpty()) {
            return "(keine)";
        }
        return ownMounts.stream().map(VolumeMount::target).sorted().toList().toString();
    }
}
