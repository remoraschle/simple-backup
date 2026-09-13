package dev.remo.simplebackup.notification;

import java.time.Instant;
import java.util.UUID;

/** Was die API ueber Kanaele und Postausgang herausgibt. */
public final class NotificationViews {

    private NotificationViews() {
    }

    public record ChannelView(
            UUID id,
            String name,
            ChannelType type,
            ChannelConfig config,
            boolean enabled,
            Severity minSeverity,
            Instant createdAt) {

        static ChannelView of(NotificationChannel channel, ChannelConfig config) {
            return new ChannelView(channel.getId(), channel.getName(), channel.getType(), config,
                    channel.isEnabled(), channel.getMinSeverity(), channel.getCreatedAt());
        }
    }

    /**
     * Ein Eintrag des Postausgangs.
     *
     * <p>Ohne Nutzlast: Die kann Pfade und Namen enthalten und wird fuer die Anzeige nicht
     * gebraucht -- Titel, Zustand und der letzte Fehler beantworten die Frage, warum nichts
     * ankam.
     */
    public record OutboxView(
            UUID id,
            UUID channelId,
            String eventType,
            Severity severity,
            String title,
            String body,
            OutboxStatus status,
            int attempts,
            String lastError,
            UUID planId,
            UUID runId,
            Instant createdAt,
            Instant sentAt) {

        static OutboxView of(OutboxEntry entry) {
            return new OutboxView(entry.getId(), entry.getChannelId(), entry.getEventType(),
                    entry.getSeverity(), entry.getTitle(), entry.getBody(), entry.getStatus(),
                    entry.getAttempts(), entry.getLastError(), entry.getPlanId(), entry.getRunId(),
                    entry.getCreatedAt(), entry.getSentAt());
        }
    }
}
