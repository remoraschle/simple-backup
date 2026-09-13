package dev.remo.simplebackup.notification;

import dev.remo.simplebackup.shared.ConflictException;
import dev.remo.simplebackup.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Nimmt Meldungen entgegen und verwaltet die Kanaele.
 *
 * <p>{@link #publish} stellt nicht zu, sondern legt nur ab. Das ist der Kern: Der Lauf, der
 * die Meldung ausloest, soll nicht auf einen fremden Dienst warten und erst recht nicht an
 * ihm scheitern. Die Zustellung uebernimmt {@link OutboxDispatcher}.
 */
@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationChannelRepository channels;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    NotificationService(NotificationChannelRepository channels, OutboxRepository outbox,
            ObjectMapper objectMapper) {
        this.channels = channels;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Legt die Meldung fuer jeden passenden Kanal in den Postausgang.
     *
     * <p>Wirft nie: Ein Fehler beim Benachrichtigen darf den Vorgang nicht kippen, der
     * benachrichtigen wollte. Ein gescheiterter Alarm ueber ein gelungenes Backup waere die
     * absurdeste Art, ein Backup als fehlgeschlagen zu melden.
     *
     * @return wie viele Kanaele angesprochen wurden
     */
    public int publish(Notification notification) {
        try {
            List<NotificationChannel> matching = channels.findAll().stream()
                    .filter(channel -> channel.accepts(notification.severity()))
                    .toList();

            if (matching.isEmpty()) {
                log.debug("Keine passenden Kanaele fuer {} ({})",
                        notification.eventType(), notification.severity());
                return 0;
            }
            String payload = objectMapper.writeValueAsString(notification.payload());
            matching.forEach(channel -> outbox.save(new OutboxEntry(channel.getId(), notification, payload)));

            return matching.size();

        } catch (RuntimeException e) {
            log.error("Meldung {} liess sich nicht ablegen", notification.eventType(), e);
            return 0;
        }
    }

    /**
     * Ob eine Meldung dieser Art zu diesem Plan seit dem genannten Zeitpunkt schon einmal
     * abgelegt wurde.
     *
     * <p>Fuer wiederkehrende Pruefungen: Ein Zustand, der stundenlang anhaelt, soll einmal
     * melden und nicht stuendlich. Wer alle zwei Stunden dieselbe Meldung bekommt, schaltet
     * den Kanal ab -- und hoert dann auch die wichtige nicht mehr.
     */
    @Transactional(readOnly = true)
    public boolean alreadyPublished(String eventType, UUID planId, java.time.Instant since) {
        return outbox.existsByEventTypeAndPlanIdAndCreatedAtAfter(eventType, planId, since);
    }

    // ------------------------------------------------------------------- Kanaele

    @Transactional(readOnly = true)
    public List<NotificationViews.ChannelView> listChannels() {
        return channels.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    public NotificationViews.ChannelView createChannel(NotificationRequests.SaveChannel request) {
        if (channels.existsByName(request.name())) {
            throw new ConflictException("Ein Kanal mit dem Namen '%s' existiert bereits".formatted(request.name()));
        }
        var channel = new NotificationChannel(request.name(), request.config().type(),
                objectMapper.writeValueAsString(request.config()), request.enabled(),
                request.minSeverity());

        return toView(channels.save(channel));
    }

    public NotificationViews.ChannelView updateChannel(UUID id, NotificationRequests.SaveChannel request) {
        NotificationChannel channel = require(id);

        if (channel.getType() != request.config().type()) {
            // Sonst zeigte ein Kanal ploetzlich woanders hin, und die Historie im
            // Postausgang wuerde unverstaendlich.
            throw new ConflictException("Die Art eines Kanals laesst sich nachtraeglich nicht aendern");
        }
        channel.update(request.name(), objectMapper.writeValueAsString(request.config()),
                request.enabled(), request.minSeverity());

        return toView(channels.save(channel));
    }

    public void deleteChannel(UUID id) {
        channels.delete(require(id));
    }

    /**
     * Legt eine Probemeldung in den Postausgang -- fuer genau diesen einen Kanal.
     *
     * <p>Bewusst ueber denselben Weg wie ein echter Alarm: Ein Test, der einen anderen Weg
     * nimmt als der Ernstfall, testet den falschen.
     */
    public UUID sendTest(UUID id) {
        NotificationChannel channel = require(id);

        var notification = new Notification("TEST", Severity.CRITICAL, "Probemeldung",
                "Wenn Sie das lesen, funktioniert der Kanal '%s'.".formatted(channel.getName()),
                null, null, Map.of("test", true));

        var entry = new OutboxEntry(channel.getId(), notification,
                objectMapper.writeValueAsString(notification.payload()));

        return outbox.save(entry).getId();
    }

    // ----------------------------------------------------------------- Postausgang

    @Transactional(readOnly = true)
    public Page<NotificationViews.OutboxView> listOutbox(Pageable pageable) {
        return outbox.findAllByOrderByCreatedAtDesc(pageable).map(NotificationViews.OutboxView::of);
    }

    private NotificationChannel require(UUID id) {
        return channels.findById(id)
                .orElseThrow(() -> new NotFoundException("Kanal %s nicht gefunden".formatted(id)));
    }

    private NotificationViews.ChannelView toView(NotificationChannel channel) {
        return NotificationViews.ChannelView.of(channel,
                objectMapper.readValue(channel.getConfig(), ChannelConfig.class));
    }
}
