package dev.remo.simplebackup.catalog;

import dev.remo.simplebackup.shared.RetentionRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Wie lange Snapshots aufbewahrt werden.
 *
 * <p>Die Datenbank laesst keine Regel zu, die nichts behaelt -- eine solche wuerde beim
 * ersten Prune saemtliche Snapshots loeschen. Die Pruefung steht als CHECK-Constraint im
 * Schema und zusaetzlich in {@link RetentionRule}, weil diese Regel zu wichtig ist, um an
 * einer einzigen Stelle zu haengen.
 */
@Entity
@Table(name = "retention_policy")
public class RetentionPolicy {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "keep_last")
    private Integer keepLast;

    @Column(name = "keep_hourly")
    private Integer keepHourly;

    @Column(name = "keep_daily")
    private Integer keepDaily;

    @Column(name = "keep_weekly")
    private Integer keepWeekly;

    @Column(name = "keep_monthly")
    private Integer keepMonthly;

    @Column(name = "keep_yearly")
    private Integer keepYearly;

    @Column(name = "keep_within_days")
    private Integer keepWithinDays;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RetentionPolicy() {
        // fuer JPA
    }

    RetentionPolicy(String name, RetentionRule rule) {
        this.id = UUID.randomUUID();
        this.name = name;
        apply(rule);
    }

    final void apply(RetentionRule rule) {
        this.keepLast = rule.keepLast();
        this.keepHourly = rule.keepHourly();
        this.keepDaily = rule.keepDaily();
        this.keepWeekly = rule.keepWeekly();
        this.keepMonthly = rule.keepMonthly();
        this.keepYearly = rule.keepYearly();
        this.keepWithinDays = rule.keepWithinDays();
    }

    /** Der Konstruktor von {@link RetentionRule} lehnt eine Regel ab, die nichts behaelt. */
    public RetentionRule toRule() {
        return new RetentionRule(keepLast, keepHourly, keepDaily, keepWeekly, keepMonthly,
                keepYearly, keepWithinDays);
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

    void rename(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
