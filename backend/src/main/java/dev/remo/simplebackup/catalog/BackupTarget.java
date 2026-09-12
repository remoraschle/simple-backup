package dev.remo.simplebackup.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Wohin gesichert wird. */
@Entity
@Table(name = "backup_target")
public class BackupTarget {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetMode mode;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String config;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "last_check_ok")
    private Boolean lastCheckOk;

    @Column(name = "last_check_message")
    private String lastCheckMessage;

    /** Freier Platz ist der haeufigste Grund fuer ploetzlich fehlschlagende Backups. */
    @Column(name = "capacity_bytes")
    private Long capacityBytes;

    @Column(name = "free_bytes")
    private Long freeBytes;

    @Column(name = "used_bytes")
    private Long usedBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BackupTarget() {
        // fuer JPA
    }

    BackupTarget(String name, TargetType type, TargetMode mode, String description, String config,
            boolean enabled) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.mode = mode;
        this.description = description;
        this.config = config;
        this.enabled = enabled;
    }

    void update(String name, String description, String config, boolean enabled) {
        this.name = name;
        this.description = description;
        this.config = config;
        this.enabled = enabled;
        this.lastCheckAt = null;
        this.lastCheckOk = null;
        this.lastCheckMessage = null;
    }

    void recordCheck(boolean successful, String message) {
        this.lastCheckAt = Instant.now();
        this.lastCheckOk = successful;
        this.lastCheckMessage = message;
    }

    void recordCapacity(Long capacity, Long free, Long used) {
        this.capacityBytes = capacity;
        this.freeBytes = free;
        this.usedBytes = used;
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

    public TargetType getType() {
        return type;
    }

    public TargetMode getMode() {
        return mode;
    }

    public String getDescription() {
        return description;
    }

    public String getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastCheckAt() {
        return lastCheckAt;
    }

    public Boolean getLastCheckOk() {
        return lastCheckOk;
    }

    public String getLastCheckMessage() {
        return lastCheckMessage;
    }

    public Long getCapacityBytes() {
        return capacityBytes;
    }

    public Long getFreeBytes() {
        return freeBytes;
    }

    public Long getUsedBytes() {
        return usedBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "BackupTarget[id=%s, name=%s, type=%s, mode=%s]".formatted(id, name, type, mode);
    }
}
