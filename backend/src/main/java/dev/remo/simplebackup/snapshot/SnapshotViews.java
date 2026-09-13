package dev.remo.simplebackup.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Was die API ueber Snapshots herausgibt. */
public final class SnapshotViews {

    private SnapshotViews() {
    }

    public record SnapshotView(
            UUID id,
            UUID runId,
            UUID planId,
            UUID targetId,
            String externalId,
            String shortId,
            Long sizeBytes,
            Instant snapshotTime,
            boolean pinned) {

        static SnapshotView of(Snapshot snapshot) {
            return new SnapshotView(snapshot.getId(), snapshot.getRunId(), snapshot.getPlanId(),
                    snapshot.getTargetId(), snapshot.getExternalId(), snapshot.getShortId(),
                    snapshot.getSizeBytes(), snapshot.getSnapshotTime(), snapshot.isPinned());
        }
    }

    /**
     * Ein Eintrag im Snapshot.
     *
     * @param path      vollstaendiger Pfad, wie er im Snapshot steht
     * @param directory ob es ein Verzeichnis ist -- entscheidet, ob man hineingehen kann
     */
    public record EntryView(
            String path,
            String name,
            boolean directory,
            Long sizeBytes,
            Instant modifiedAt) {
    }

    public record BrowseResult(String snapshotId, String path, List<EntryView> entries) {
    }
}
