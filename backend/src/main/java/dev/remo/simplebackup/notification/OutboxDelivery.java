package dev.remo.simplebackup.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Der eigentliche Versand, jede Meldung in ihrer eigenen Transaktion.
 *
 * <p><b>Eine eigene Bean und keine Methoden im Zusteller selbst.</b> Spring legt
 * {@code @Transactional} als Stellvertreter um die Bean; ein Aufruf innerhalb derselben
 * Klasse geht daran vorbei und liefe ohne Transaktion -- der Eintrag bliebe dann fuer immer
 * auf {@code PENDING} stehen.
 */
@Component
class OutboxDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);

    /** Wie lange ein uebernommener Eintrag fuer andere gesperrt bleibt. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final OutboxRepository outbox;
    private final NotificationChannelRepository channels;
    private final Map<ChannelType, NotificationTransport> transports;
    private final ObjectMapper objectMapper;

    OutboxDelivery(OutboxRepository outbox, NotificationChannelRepository channels,
            List<NotificationTransport> transports, ObjectMapper objectMapper) {

        this.outbox = outbox;
        this.channels = channels;
        this.transports = transports.stream()
                .collect(Collectors.toUnmodifiableMap(NotificationTransport::type, Function.identity()));
        this.objectMapper = objectMapper;
    }

    /** Uebernimmt faellige Meldungen und sperrt sie fuer die Dauer der Zustellung. */
    @Transactional
    List<UUID> claimDue(int limit) {
        List<OutboxEntry> due = outbox.findDueForUpdate(Instant.now(), limit);
        due.forEach(entry -> entry.lease(LEASE));
        outbox.saveAll(due);

        return due.stream().map(OutboxEntry::getId).toList();
    }

    /** Stellt eine einzelne Meldung zu. Wirft nicht -- das Ergebnis steht im Eintrag. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deliver(UUID entryId) {
        OutboxEntry entry = outbox.findById(entryId).orElse(null);
        if (entry == null || entry.getStatus() != OutboxStatus.PENDING) {
            return;
        }

        NotificationChannel channel = channels.findById(entry.getChannelId()).orElse(null);
        if (channel == null) {
            entry.markFailed("Der Kanal existiert nicht mehr");
            outbox.save(entry);
            return;
        }

        NotificationTransport transport = transports.get(channel.getType());
        if (transport == null) {
            entry.markFailed("Fuer " + channel.getType() + " gibt es keinen Versandweg");
            outbox.save(entry);
            return;
        }

        try {
            ChannelConfig config = objectMapper.readValue(channel.getConfig(), ChannelConfig.class);
            entry.markSent(transport.send(config, entry));

            log.info("Meldung {} ueber {} zugestellt", entry.getEventType(), channel.getName());

        } catch (RuntimeException e) {
            entry.markFailed(String.valueOf(e.getMessage()));

            if (entry.getStatus() == OutboxStatus.ABANDONED) {
                // Sichtbar machen: Ab hier kommt ueber diesen Kanal nichts mehr an, und das
                // faellt sonst erst auf, wenn man den Alarm braucht.
                log.error("Meldung {} ueber {} endgueltig aufgegeben: {}",
                        entry.getEventType(), channel.getName(), entry.getLastError());
            } else {
                log.warn("Meldung {} ueber {} fehlgeschlagen, Versuch {}: {}",
                        entry.getEventType(), channel.getName(), entry.getAttempts(), entry.getLastError());
            }
        }
        outbox.save(entry);
    }
}
