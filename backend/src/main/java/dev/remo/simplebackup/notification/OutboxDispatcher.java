package dev.remo.simplebackup.notification;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Leert den Postausgang in regelmaessigen Abstaenden.
 *
 * <p>Hier steht nur der Takt; die Arbeit macht {@link OutboxDelivery}, weil jede Meldung
 * ihre eigene Transaktion braucht: Bleibt ein Kanal haengen, sollen die uebrigen Meldungen
 * trotzdem hinausgehen, und ein bereits erfolgter Versand darf nicht durch einen spaeteren
 * Fehler zurueckgerollt werden -- zuruecknehmen laesst er sich ohnehin nicht.
 */
@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxDelivery delivery;
    private final NotificationProperties properties;

    OutboxDispatcher(OutboxDelivery delivery, NotificationProperties properties) {
        this.delivery = delivery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${simplebackup.notification.dispatch-interval:PT20S}")
    void dispatchDue() {
        List<UUID> due;
        try {
            due = delivery.claimDue(properties.batchSize());
        } catch (RuntimeException e) {
            // Ein Fehler darf den Zusteller nicht dauerhaft anhalten -- sonst kaeme ab
            // diesem Moment kein Alarm mehr an.
            log.error("Postausgang liess sich nicht lesen", e);
            return;
        }

        for (UUID entryId : due) {
            try {
                delivery.deliver(entryId);
            } catch (RuntimeException e) {
                log.error("Meldung {} liess sich nicht zustellen", entryId, e);
            }
        }
    }
}
