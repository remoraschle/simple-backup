package dev.remo.simplebackup.security;

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

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
        // fuer JPA
    }

    AppUser(String username, String passwordHash, UserRole role, boolean mustChangePassword) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    /** Sperre laeuft ab, ohne dass jemand sie aufheben muss. */
    boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    void recordFailedLogin(int maxAttempts, java.time.Duration lockoutDuration) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = Instant.now().plus(lockoutDuration);
        }
    }

    void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = Instant.now();
    }

    /**
     * Die Rolle kommt vom Anbieter.
     *
     * <p>Nur dort verwendet: Wo eine Gruppe des Anbieters ueber die Rolle entscheidet, ist
     * er die Wahrheit -- sonst wuerde eine Rechteaenderung dort hier nie ankommen.
     */
    void changeRole(UserRole newRole) {
        this.role = newRole;
    }

    void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
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

    public String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /** Ohne Passworthash -- diese Darstellung landet in Logs. */
    @Override
    public String toString() {
        return "AppUser[username=%s, role=%s]".formatted(username, role);
    }
}
