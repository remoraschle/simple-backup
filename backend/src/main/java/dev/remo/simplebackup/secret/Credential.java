package dev.remo.simplebackup.secret;

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

/**
 * Ein verschluesselt abgelegter Zugang.
 *
 * <p>Diese Entitaet haelt ausschliesslich Chiffrat. Sie besitzt bewusst keine Methode, die
 * Klartext liefert -- entschluesselt wird nur ueber {@link CredentialService}, damit es
 * genau eine Stelle gibt, an der Klartext entsteht.
 */
@Entity
@Table(name = "credential")
public class Credential {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialType type;

    private String description;

    @Column(name = "wrapped_dek", nullable = false)
    private byte[] wrappedDek;

    @Column(name = "dek_iv", nullable = false)
    private byte[] dekIv;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(name = "payload_iv", nullable = false)
    private byte[] payloadIv;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Credential() {
        // fuer JPA
    }

    Credential(String name, CredentialType type, String description, EncryptedSecret secret) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.description = description;
        applySecret(secret);
    }

    final void applySecret(EncryptedSecret secret) {
        this.wrappedDek = secret.wrappedDek();
        this.dekIv = secret.dekIv();
        this.ciphertext = secret.ciphertext();
        this.payloadIv = secret.payloadIv();
        this.keyVersion = secret.keyVersion();
    }

    EncryptedSecret toEncryptedSecret() {
        return new EncryptedSecret(wrappedDek, dekIv, ciphertext, payloadIv, keyVersion);
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

    public void setName(String name) {
        this.name = name;
    }

    public CredentialType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Bewusst ohne Chiffrat und ohne Schluesselmaterial: Diese Darstellung landet in Logs
     * und Fehlermeldungen.
     */
    @Override
    public String toString() {
        return "Credential[id=%s, name=%s, type=%s]".formatted(id, name, type);
    }
}
