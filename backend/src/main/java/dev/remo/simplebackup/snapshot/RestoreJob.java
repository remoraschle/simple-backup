package dev.remo.simplebackup.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Eine laufende oder beendete Wiederherstellung.
 *
 * <p>Bewusst nur im Speicher und nicht in der Datenbank: Eine Wiederherstellung ist ein
 * Handgriff, den jemand bewusst ausloest und dabei zusieht -- kein Vorgang, der nachts allein
 * laeuft. Wer sie spaeter belegen muss, findet sie im Audit-Log. Startet das Backend waehrend
 * einer Wiederherstellung neu, laeuft restic im Runner weiter, nur die Anzeige ist weg.
 */
public final class RestoreJob {

    /** Mehr Zeilen braucht niemand im Blick zu behalten; das Protokoll steht im Runner. */
    private static final int MAX_LINES = 2000;

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final UUID id = UUID.randomUUID();
    private final UUID snapshotId;
    private final String targetPath;
    private final List<String> includes;
    private final Instant startedAt = Instant.now();
    private final List<String> log = new CopyOnWriteArrayList<>();

    private volatile State state = State.RUNNING;
    private volatile String message;
    private volatile Instant finishedAt;

    RestoreJob(UUID snapshotId, String targetPath, List<String> includes) {
        this.snapshotId = snapshotId;
        this.targetPath = targetPath;
        this.includes = List.copyOf(includes);
    }

    void append(String line) {
        if (log.size() < MAX_LINES) {
            log.add(line);
        }
    }

    void finish(boolean successful, String message) {
        this.state = successful ? State.SUCCEEDED : State.FAILED;
        this.message = message;
        this.finishedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<String> getLog() {
        return List.copyOf(log);
    }
}
