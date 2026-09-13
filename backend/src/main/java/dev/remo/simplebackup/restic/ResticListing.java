package dev.remo.simplebackup.restic;

import java.time.Instant;
import java.util.List;

/** Was restic beim Auflisten meldet. */
public final class ResticListing {

    private ResticListing() {
    }

    /**
     * Ein Snapshot, wie {@code restic snapshots --json} ihn beschreibt.
     *
     * @param id vollstaendige Kennung; {@code shortId} waere nicht eindeutig genug, um damit
     *           wiederherzustellen
     */
    public record Snapshot(
            String id,
            Instant time,
            String hostname,
            List<String> tags,
            List<String> paths) {

        public Snapshot {
            tags = tags == null ? List.of() : List.copyOf(tags);
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /**
     * Ein Eintrag im Snapshot, wie {@code restic ls --json} ihn meldet.
     *
     * @param path vollstaendiger Pfad im Snapshot, mit fuehrendem Schraegstrich
     */
    public record Node(String path, String name, boolean directory, Long size, Instant modified) {

        /** Die Ebene im Baum: {@code /home/remo} liegt auf Ebene 2. */
        public int depth() {
            return (int) path.chars().filter(character -> character == '/').count();
        }
    }
}
