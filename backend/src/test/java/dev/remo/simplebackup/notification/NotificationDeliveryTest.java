package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Der Weg einer Meldung von Anfang bis Ende: ablegen, zustellen, wiederholen.
 *
 * <p>Gegen die echte Datenbank und einen echten HTTP-Server, weil genau dazwischen die
 * Fehler sitzen -- Sperren, Wiederholungen und der Zustand eines Eintrags.
 */
class NotificationDeliveryTest extends IntegrationTestBase {

    @Autowired
    private NotificationService service;

    @Autowired
    private OutboxDelivery delivery;

    @Autowired
    private NotificationChannelRepository channels;

    @Autowired
    private OutboxRepository outbox;

    private FakeEndpoint endpoint;
    private String prefix;

    @BeforeEach
    void setUp() {
        endpoint = new FakeEndpoint();
        prefix = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        endpoint.close();
        channels.findAll().stream()
                .filter(channel -> channel.getName().startsWith("test-"))
                .forEach(channel -> {
                    outbox.findAll().stream()
                            .filter(entry -> entry.getChannelId().equals(channel.getId()))
                            .forEach(outbox::delete);
                    channels.delete(channel);
                });
    }

    private UUID webhookChannel(Severity minSeverity) {
        return service.createChannel(new NotificationRequests.SaveChannel(prefix,
                new ChannelConfig.Webhook(endpoint.url(), null, null), true, minSeverity)).id();
    }

    private Notification notification(Severity severity) {
        return new Notification("RUN_FAILED", severity, "Sicherung fehlgeschlagen",
                "NAS: fehlgeschlagen", null, null, Map.of("planName", "Nachts"));
    }

    private OutboxEntry entryOf(UUID channelId) {
        return outbox.findAll().stream()
                .filter(entry -> entry.getChannelId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Eintrag im Postausgang"));
    }

    @Test
    @DisplayName("Eine Meldung landet im Postausgang und wird von dort zugestellt")
    void publishesAndDelivers() {
        UUID channelId = webhookChannel(Severity.WARNING);

        assertThat(service.publish(notification(Severity.CRITICAL))).isEqualTo(1);

        OutboxEntry queued = entryOf(channelId);
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
        // Zum Zeitpunkt des Ablegens darf noch nichts gesendet worden sein: Der Lauf soll
        // nicht auf einen fremden Dienst warten.
        assertThat(endpoint.requests()).isEmpty();

        delivery.deliver(queued.getId());

        assertThat(outbox.findById(queued.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
        assertThat(endpoint.requests()).hasSize(1);
    }

    @Test
    @DisplayName("Meldungen unterhalb der Mindeststufe erreichen den Kanal nicht")
    void respectsMinimumSeverity() {
        webhookChannel(Severity.CRITICAL);

        assertThat(service.publish(notification(Severity.INFO))).isZero();
        assertThat(service.publish(notification(Severity.WARNING))).isZero();
        assertThat(service.publish(notification(Severity.CRITICAL))).isEqualTo(1);
    }

    @Test
    @DisplayName("Eine gescheiterte Zustellung bleibt liegen und wird spaeter erneut versucht")
    void retriesAfterFailure() {
        UUID channelId = webhookChannel(Severity.WARNING);
        endpoint.respondWith(503, "gerade nicht");

        service.publish(notification(Severity.CRITICAL));
        UUID entryId = entryOf(channelId).getId();

        delivery.deliver(entryId);

        OutboxEntry afterFailure = outbox.findById(entryId).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("503");
        // Nicht sofort wieder: Der naechste Versuch liegt in der Zukunft.
        assertThat(afterFailure.getNextAttemptAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("Ein uebernommener Eintrag wird nicht gleich noch einmal uebernommen")
    void claimingLeasesTheEntry() {
        // Sonst ginge derselbe Alarm doppelt hinaus, sobald zwei Instanzen laufen.
        UUID channelId = webhookChannel(Severity.WARNING);
        service.publish(notification(Severity.CRITICAL));
        UUID entryId = entryOf(channelId).getId();

        assertThat(delivery.claimDue(10)).contains(entryId);
        assertThat(delivery.claimDue(10)).doesNotContain(entryId);
    }

    @Test
    @DisplayName("Die Probemeldung nimmt denselben Weg wie ein echter Alarm")
    void testMessageUsesTheRealPath() {
        // Ein Test, der einen anderen Weg nimmt als der Ernstfall, testet den falschen.
        UUID channelId = webhookChannel(Severity.WARNING);

        UUID entryId = service.sendTest(channelId);
        delivery.deliver(entryId);

        assertThat(endpoint.lastRequest().body()).contains("\"event\":\"TEST\"");
    }

    @Test
    @DisplayName("Ein abgeschalteter Kanal bekommt nichts")
    void disabledChannelGetsNothing() {
        service.createChannel(new NotificationRequests.SaveChannel(prefix,
                new ChannelConfig.Webhook(endpoint.url(), null, null), false, Severity.INFO));

        assertThat(service.publish(notification(Severity.CRITICAL))).isZero();
    }
}
