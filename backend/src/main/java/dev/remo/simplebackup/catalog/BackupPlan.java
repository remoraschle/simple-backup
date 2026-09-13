package dev.remo.simplebackup.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Was, wohin und wann gesichert wird.
 *
 * <p>Ein Plan schreibt auf <b>mehrere</b> Ziele. Ein Backup auf genau ein Ziel ist kein
 * Backup -- die Quelle wird einmal beschafft und das Ergebnis auf alle Ziele geschrieben.
 */
@Entity
@Table(name = "backup_plan")
public class BackupPlan {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private BackupSource source;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "plan_target",
            joinColumns = @JoinColumn(name = "plan_id"),
            inverseJoinColumns = @JoinColumn(name = "target_id"))
    private List<BackupTarget> targets = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retention_policy_id")
    private RetentionPolicy retentionPolicy;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private String timezone = "Europe/Zurich";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "timeout_minutes", nullable = false)
    private int timeoutMinutes = 360;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 2;

    @Enumerated(EnumType.STRING)
    @Column(name = "missed_run_policy", nullable = false)
    private MissedRunPolicy missedRunPolicy = MissedRunPolicy.SKIP;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_on", nullable = false)
    private NotifyOn notifyOn = NotifyOn.FAILURE;

    /**
     * Erwarteter Abstand zwischen Laeufen, Grundlage des internen Totmannschalters.
     *
     * <p>Wird er deutlich ueberschritten, gilt der Plan als ueberfaellig -- der gefaehrlichste
     * Zustand ist nicht das fehlgeschlagene, sondern das ausgebliebene Backup.
     */
    @Column(name = "expected_interval_minutes")
    private Integer expectedIntervalMinutes;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_run_status")
    private String lastRunStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BackupPlan() {
        // fuer JPA
    }

    BackupPlan(String name, BackupSource source, List<BackupTarget> targets, String cronExpression,
            String timezone) {
        this(UUID.randomUUID(), name, source, targets, cronExpression, timezone);
    }

    /**
     * Mit vorgegebener Kennung -- nur fuers Einspielen eines Archivs.
     *
     * <p>Die Kennung ist bei einem Plan nicht beliebig: Aus ihr leiten sich Host und Tag
     * seiner Snapshots ab. Ein eingespielter Plan mit neuer Kennung waere fuer sein eigenes
     * Repository ein Fremder.
     */
    BackupPlan(UUID id, String name, BackupSource source, List<BackupTarget> targets,
            String cronExpression, String timezone) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.targets = new ArrayList<>(targets);
        this.cronExpression = cronExpression;
        this.timezone = timezone;
    }

    /**
     * Kennung, unter der restic die Snapshots dieses Plans ablegt.
     *
     * <p>Muss stabil bleiben: Aendert sie sich, gelten die bisherigen Snapshots als fremd,
     * und die Aufbewahrungsregel greift nicht mehr auf sie zu. Deshalb die unveraenderliche
     * Kennung und nicht der Name, den man umbenennen kann.
     */
    public String resticHost() {
        return "plan-" + id;
    }

    /** Kennzeichnung der Snapshots. Begrenzt auch, was {@code forget} loeschen darf. */
    public String resticTag() {
        return "plan-" + id;
    }

    void scheduleNext(Instant next) {
        this.nextRunAt = next;
    }

    void recordRun(Instant at, String status) {
        this.lastRunAt = at;
        this.lastRunStatus = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BackupSource getSource() {
        return source;
    }

    public List<BackupTarget> getTargets() {
        return List.copyOf(targets);
    }

    public RetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public MissedRunPolicy getMissedRunPolicy() {
        return missedRunPolicy;
    }

    public NotifyOn getNotifyOn() {
        return notifyOn;
    }

    public Integer getExpectedIntervalMinutes() {
        return expectedIntervalMinutes;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(String name, String description, List<BackupTarget> targets, String cronExpression,
            String timezone, boolean enabled, int timeoutMinutes, int maxRetries,
            MissedRunPolicy missedRunPolicy, NotifyOn notifyOn, Integer expectedIntervalMinutes,
            RetentionPolicy retentionPolicy) {
        this.name = name;
        this.description = description;
        this.targets = new ArrayList<>(targets);
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.enabled = enabled;
        this.timeoutMinutes = timeoutMinutes;
        this.maxRetries = maxRetries;
        this.missedRunPolicy = missedRunPolicy;
        this.notifyOn = notifyOn;
        this.expectedIntervalMinutes = expectedIntervalMinutes;
        this.retentionPolicy = retentionPolicy;
    }

    @Override
    public String toString() {
        return "BackupPlan[id=%s, name=%s]".formatted(id, name);
    }
}
