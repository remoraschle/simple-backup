package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceType;
import org.springframework.stereotype.Component;

/** Ein S3-Bucket als Quelle. Die Arbeit macht {@link RcloneProducer}. */
@Component
class S3Producer implements SourceProducer {

    private final RcloneProducer rclone;

    S3Producer(RcloneProducer rclone) {
        this.rclone = rclone;
    }

    @Override
    public SourceType type() {
        return SourceType.S3;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {
        return rclone.fetch(plan, stagingDirectory, listener);
    }
}
