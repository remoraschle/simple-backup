package dev.remo.simplebackup.run;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ein Durchgang eines Plans. */
@Entity
@Table(name = "backup_run")
public class BackupRun {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "bytes_processed")
    private Long bytesProcessed;

    /** Nach Deduplizierung tatsaechlich uebertragen -- die aussagekraeftige Zahl. */
    @Column(name = "bytes_transferred")
    private Long bytesTransferred;

    @Column(name = "files_new")
    private Long filesNew;

    @Column(name = "files_changed")
    private Long filesChanged;

    @Column(name = "files_unchanged")
    private Long filesUnchanged;

    /** Nur der Pfad. Logs gehoeren nicht in die Datenbank. */
    @Column(name = "log_path")
    private String logPath;

    @Column(name = "error_summary")
    private String errorSummary;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    private List<RunStep> steps = new ArrayList<>();

    protected BackupRun() {
        // fuer JPA
    }

    BackupRun(UUID planId, RunTrigger triggerType, int attempt) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.triggerType = triggerType;
        this.attempt = attempt;
        this.status = RunStatus.QUEUED;
        this.queuedAt = Instant.now();
    }

    RunStep addStep(StepKind kind, UUID targetId, String description) {
        var step = new RunStep(this, steps.size() + 1, kind, targetId, description);
        steps.add(step);
        return step;
    }

    void markRunning() {
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    void finish(RunStatus finalStatus, String errorSummary) {
        this.status = finalStatus;
        this.finishedAt = Instant.now();
        this.errorSummary = errorSummary;
    }

    void recordMetrics(Long bytesProcessed, Long bytesTransferred, Long filesNew, Long filesChanged,
            Long filesUnchanged) {
        this.bytesProcessed = bytesProcessed;
        this.bytesTransferred = bytesTransferred;
        this.filesNew = filesNew;
        this.filesChanged = filesChanged;
        this.filesUnchanged = filesUnchanged;
    }

    void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    /**
     * Leitet den Gesamtzustand aus den Schritten ab.
     *
     * <p>Die Regel ist der Grund fuer die Aufteilung in Schritte: Gelingt die Beschaffung und
     * schlaegt nur eines von mehreren Zielen fehl, ist der Lauf weder ein Erfolg noch ein
     * Fehlschlag, sondern ein Teilerfolg.
     */
    RunStatus deriveStatus() {
        List<RunStep> transfers = steps.stream().filter(step -> step.getKind() == StepKind.TRANSFER).toList();

        if (steps.stream().anyMatch(step -> step.getStatus() == StepStatus.CANCELLED)) {
            return RunStatus.CANCELLED;
        }
        if (steps.stream().anyMatch(step -> step.getStatus() == StepStatus.TIMEOUT)) {
            return RunStatus.TIMEOUT;
        }

        // Scheitert die Beschaffung, gibt es nichts zu uebertragen: ein klarer Fehlschlag.
        boolean acquisitionFailed = steps.stream()
                .filter(step -> step.getKind() == StepKind.ACQUIRE)
                .anyMatch(step -> step.getStatus() == StepStatus.FAILED);
        if (acquisitionFailed || transfers.isEmpty()) {
            return RunStatus.FAILED;
        }

        long successful = transfers.stream().filter(step -> step.getStatus() == StepStatus.SUCCESS).count();

        if (successful == transfers.size()) {
            return RunStatus.SUCCESS;
        }
        return successful == 0 ? RunStatus.FAILED : RunStatus.PARTIAL;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public RunTrigger getTriggerType() {
        return triggerType;
    }

    public RunStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Duration getDuration() {
        return startedAt == null ? Duration.ZERO
                : Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt);
    }

    public Long getBytesProcessed() {
        return bytesProcessed;
    }

    public Long getBytesTransferred() {
        return bytesTransferred;
    }

    public Long getFilesNew() {
        return filesNew;
    }

    public Long getFilesChanged() {
        return filesChanged;
    }

    public Long getFilesUnchanged() {
        return filesUnchanged;
    }

    public String getLogPath() {
        return logPath;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public List<RunStep> getSteps() {
        return List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "BackupRun[id=%s, plan=%s, status=%s]".formatted(id, planId, status);
    }
}
