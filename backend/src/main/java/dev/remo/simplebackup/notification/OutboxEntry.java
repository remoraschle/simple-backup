package dev.remo.simplebackup.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Eine Meldung auf dem Weg zu einem Kanal.
 *
 * <p>Steht erst in der Datenbank und wird dann zugestellt, nicht umgekehrt. Faellt das Netz
 * aus oder antwortet der Dienst nicht, bleibt der Eintrag liegen und wird spaeter erneut
 * versucht -- mit wachsendem Abstand, damit ein dauerhaft kaputter Kanal nicht im Sekundentakt
 * angeklopft wird.
 */
@Entity
@Table(name = "notification_outbox")
class OutboxEntry {

    /** Danach gilt eine Meldung als nicht zustellbar. */
    static final int MAX_ATTEMPTS = 8;

    @Id
    private UUID id;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "external_receipt")
    private String externalReceipt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEntry() {
        // fuer JPA
    }

    OutboxEntry(UUID channelId, Notification notification, String payload) {
        this.id = UUID.randomUUID();
        this.channelId = channelId;
        this.eventType = notification.eventType();
        this.severity = notification.severity();
        this.title = notification.title();
        this.body = notification.body();
        this.payload = payload;
        this.planId = notification.planId();
        this.runId = notification.runId();
        this.createdAt = Instant.now();
        this.nextAttemptAt = this.createdAt;
    }

    /**
     * Schiebt den naechsten Versuch nach hinten, waehrend dieser Eintrag zugestellt wird.
     *
     * <p>Die Sperre der Abfrage endet mit ihrer Transaktion, der Versand dauert aber
     * laenger. Ohne diese Frist griffe sich eine zweite Instanz -- oder der naechste
     * Durchgang derselben -- denselben Eintrag und der Alarm ginge doppelt hinaus.
     */
    void lease(Duration duration) {
        this.nextAttemptAt = Instant.now().plus(duration);
    }

    void markSent(String receipt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.attempts++;
        this.externalReceipt = receipt;
        this.lastError = null;
    }

    /**
     * Haelt einen gescheiterten Versuch fest und legt den naechsten Zeitpunkt fest.
     *
     * <p>Der Abstand verdoppelt sich mit jedem Versuch und ist bei einer Stunde gedeckelt.
     * Nach {@link #MAX_ATTEMPTS} Versuchen wird aufgegeben -- aber sichtbar: Der Eintrag
     * bleibt mit seinem letzten Fehler stehen.
     */
    void markFailed(String error) {
        this.attempts++;
        this.lastError = error;

        if (attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.ABANDONED;
            return;
        }
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = Instant.now().plus(backoff(attempts));
    }

    static Duration backoff(int attempts) {
        long seconds = (long) Math.min(30 * Math.pow(2, attempts - 1.0), 3600);
        return Duration.ofSeconds(seconds);
    }

    UUID getId() {
        return id;
    }

    UUID getChannelId() {
        return channelId;
    }

    String getEventType() {
        return eventType;
    }

    Severity getSeverity() {
        return severity;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    String getPayload() {
        return payload;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getRunId() {
        return runId;
    }

    OutboxStatus getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    String getLastError() {
        return lastError;
    }

    String getExternalReceipt() {
        return externalReceipt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getSentAt() {
        return sentAt;
    }
}
