package dev.remo.simplebackup.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ein Schritt innerhalb eines Laufs. */
@Entity
@Table(name = "run_step")
public class RunStep {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private BackupRun run;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepKind kind;

    @Column(name = "target_id")
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    /**
     * Container-Kennung des Runners.
     *
     * <p>Traegt das Wiederanhaengen nach einem Backend-Neustart: Laufende Container werden
     * ueber ihr Label gefunden und hierueber dem Schritt zugeordnet.
     */
    @Column(name = "container_id")
    private String containerId;

    private String image;

    /** Die Argumentliste, bereits von Geheimnissen bereinigt. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] command;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "bytes_transferred")
    private Long bytesTransferred;

    private String message;

    protected RunStep() {
        // fuer JPA
    }

    RunStep(BackupRun run, int seq, StepKind kind, UUID targetId, String message) {
        this.id = UUID.randomUUID();
        this.run = run;
        this.seq = seq;
        this.kind = kind;
        this.targetId = targetId;
        this.message = message;
        this.status = StepStatus.PENDING;
    }

    void markRunning(String image, List<String> redactedCommand, String containerId) {
        this.status = StepStatus.RUNNING;
        this.startedAt = Instant.now();
        this.image = image;
        this.command = redactedCommand.toArray(String[]::new);
        this.containerId = containerId;
    }

    void finish(StepStatus status, Integer exitCode, String message) {
        this.status = status;
        this.exitCode = exitCode;
        this.message = message;
        this.finishedAt = Instant.now();
        // Der Container existiert nach der Auswertung nicht mehr.
        this.containerId = null;
    }

    void skip(String reason) {
        this.status = StepStatus.SKIPPED;
        this.message = reason;
        this.finishedAt = Instant.now();
    }

    void recordBytes(Long bytes) {
        this.bytesTransferred = bytes;
    }

    public UUID getId() {
        return id;
    }

    public int getSeq() {
        return seq;
    }

    public StepKind getKind() {
        return kind;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getContainerId() {
        return containerId;
    }

    public String getImage() {
        return image;
    }

    public List<String> getCommand() {
        return command == null ? List.of() : List.of(command);
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getBytesTransferred() {
        return bytesTransferred;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "RunStep[seq=%d, kind=%s, status=%s]".formatted(seq, kind, status);
    }
}
