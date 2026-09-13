package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceType;
import org.springframework.stereotype.Component;

/** Ein Verzeichnis auf einem SFTP-Server. Die Arbeit macht {@link RcloneProducer}. */
@Component
class SftpProducer implements SourceProducer {

    private final RcloneProducer rclone;

    SftpProducer(RcloneProducer rclone) {
        this.rclone = rclone;
    }

    @Override
    public SourceType type() {
        return SourceType.SFTP;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {
        return rclone.fetch(plan, stagingDirectory, listener);
    }
}
