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

/**
 * Was gesichert wird.
 *
 * <p>Die typspezifische Konfiguration liegt als JSONB. In der Entitaet ist sie eine
 * Zeichenkette; typisiert wird sie erst im Dienst, wo auch die Pruefung stattfindet. Das
 * haelt die Entitaet frei von Umwandlungslogik und einen neuen Quelltyp frei von
 * Schemaaenderungen.
 */
@Entity
@Table(name = "backup_source")
public class BackupSource {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType type;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String config;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "last_check_ok")
    private Boolean lastCheckOk;

    @Column(name = "last_check_message")
    private String lastCheckMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BackupSource() {
        // fuer JPA
    }

    BackupSource(String name, SourceType type, String description, String config) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.description = description;
        this.config = config;
    }

    void update(String name, String description, String config) {
        this.name = name;
        this.description = description;
        this.config = config;
        // Nach einer Aenderung ist das frühere Pruefergebnis wertlos.
        this.lastCheckAt = null;
        this.lastCheckOk = null;
        this.lastCheckMessage = null;
    }

    void recordCheck(boolean successful, String message) {
        this.lastCheckAt = Instant.now();
        this.lastCheckOk = successful;
        this.lastCheckMessage = message;
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

    public SourceType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getConfig() {
        return config;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "BackupSource[id=%s, name=%s, type=%s]".formatted(id, name, type);
    }
}
