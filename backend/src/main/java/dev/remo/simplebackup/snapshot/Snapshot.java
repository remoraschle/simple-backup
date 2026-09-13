package dev.remo.simplebackup.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ein Stand im Repository, wie ihn restic angelegt hat.
 *
 * <p>Die Datenbank ist hier nur Verzeichnis, nicht Wahrheit: Die Wahrheit steht im
 * Repository, und wer dieses Werkzeug verliert, kommt mit restic allein an dieselben
 * Snapshots. Das Verzeichnis erspart im Normalfall den Aufruf und beantwortet die Frage
 * „wovon gibt es ueberhaupt etwas" ohne Netz und ohne Passwort.
 */
@Entity
@Table(name = "snapshot")
public class Snapshot {

    @Id
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    /** Die vollstaendige Kennung, mit der restic den Snapshot anspricht. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /** Die verkuerzte Kennung, die restic selbst anzeigt. */
    @Column(name = "short_id")
    private String shortId;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "snapshot_time", nullable = false)
    private Instant snapshotTime;

    /** Von der Aufbewahrung ausgenommen, etwa der Stand vor einer Migration. */
    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Snapshot() {
        // fuer JPA
    }

    Snapshot(UUID runId, UUID planId, UUID targetId, String externalId, Long sizeBytes,
            Instant snapshotTime) {

        this.id = UUID.randomUUID();
        this.runId = runId;
        this.planId = planId;
        this.targetId = targetId;
        this.externalId = externalId;
        this.shortId = shortIdOf(externalId);
        this.sizeBytes = sizeBytes;
        this.snapshotTime = snapshotTime;
        this.createdAt = Instant.now();
    }

    /** restic zeigt die ersten acht Zeichen an; damit laesst sich ein Snapshot auch ansprechen. */
    static String shortIdOf(String externalId) {
        return externalId == null || externalId.length() <= 8 ? externalId : externalId.substring(0, 8);
    }

    void pin(boolean pinned) {
        this.pinned = pinned;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getShortId() {
        return shortId;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getSnapshotTime() {
        return snapshotTime;
    }

    public boolean isPinned() {
        return pinned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
