package dev.remo.simplebackup.notification;

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

/** Wohin Meldungen gehen. */
@Entity
@Table(name = "notification_channel")
class NotificationChannel {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String config;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity", nullable = false)
    private Severity minSeverity = Severity.WARNING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationChannel() {
        // fuer JPA
    }

    NotificationChannel(String name, ChannelType type, String config, boolean enabled,
            Severity minSeverity) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.config = config;
        this.enabled = enabled;
        this.minSeverity = minSeverity;
    }

    void update(String name, String config, boolean enabled, Severity minSeverity) {
        this.name = name;
        this.config = config;
        this.enabled = enabled;
        this.minSeverity = minSeverity;
    }

    /** Ob dieser Kanal eine Meldung dieser Stufe bekommt. */
    boolean accepts(Severity severity) {
        return enabled && severity.reaches(minSeverity);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    ChannelType getType() {
        return type;
    }

    String getConfig() {
        return config;
    }

    boolean isEnabled() {
        return enabled;
    }

    Severity getMinSeverity() {
        return minSeverity;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
