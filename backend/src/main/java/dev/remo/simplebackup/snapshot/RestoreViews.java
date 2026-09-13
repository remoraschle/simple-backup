package dev.remo.simplebackup.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Was die API ueber Wiederherstellungen herausgibt. */
public final class RestoreViews {

    private RestoreViews() {
    }

    public record RestoreView(
            UUID id,
            UUID snapshotId,
            String targetPath,
            List<String> includes,
            RestoreJob.State state,
            String message,
            Instant startedAt,
            Instant finishedAt) {

        static RestoreView of(RestoreJob job) {
            return new RestoreView(job.getId(), job.getSnapshotId(), job.getTargetPath(),
                    job.getIncludes(), job.getState(), job.getMessage(), job.getStartedAt(),
                    job.getFinishedAt());
        }
    }
}
