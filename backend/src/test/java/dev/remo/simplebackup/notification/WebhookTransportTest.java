package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.secret.CredentialService;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WebhookTransportTest {

    private static final UUID TOKEN_ID = UUID.randomUUID();

    private FakeEndpoint endpoint;
    private WebhookTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        endpoint = new FakeEndpoint();

        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(TOKEN_ID)).thenReturn("Bearer geheim");

        transport = new WebhookTransport(HttpClient.newHttpClient(), credentials, objectMapper);
    }

    @AfterEach
    void tearDown() {
        endpoint.close();
    }

    private OutboxEntry entry(Severity severity) {
        var notification = new Notification("RUN_FAILED", severity, "Sicherung fehlgeschlagen",
                "NAS: fehlgeschlagen", UUID.randomUUID(), UUID.randomUUID(), Map.of("planName", "Nachts"));

        return new OutboxEntry(UUID.randomUUID(), notification,
                objectMapper.writeValueAsString(notification.payload()));
    }

    @Test
    @DisplayName("Sendet die Meldung als JSON")
    void sendsJson() {
        transport.send(new ChannelConfig.Webhook(endpoint.url(), null, null), entry(Severity.CRITICAL));

        var body = objectMapper.readTree(endpoint.lastRequest().body());

        assertThat(body.get("event").asString()).isEqualTo("RUN_FAILED");
        assertThat(body.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(body.get("title").asString()).isEqualTo("Sicherung fehlgeschlagen");
        assertThat(body.get("message").asString()).isEqualTo("NAS: fehlgeschlagen");
        assertThat(body.get("occurredAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("Die Nutzlast steht unter data und nicht auf oberster Ebene")
    void payloadIsNested() {
        // Sonst koennte ein zusaetzliches Feld eines Tages ein festes ueberschreiben.
        transport.send(new ChannelConfig.Webhook(endpoint.url(), null, null), entry(Severity.WARNING));

        var body = objectMapper.readTree(endpoint.lastRequest().body());

        assertThat(body.get("data").get("planName").asString()).isEqualTo("Nachts");
    }

    @Test
    @DisplayName("Ein hinterlegter Wert geht als Kopffeld mit, nicht im Rumpf")
    void sendsHeaderFromCredential() {
        transport.send(new ChannelConfig.Webhook(endpoint.url(), "Authorization", TOKEN_ID),
                entry(Severity.CRITICAL));

        var request = endpoint.lastRequest();

        assertThat(request.headers().get("Authorization")).containsExactly("Bearer geheim");
        assertThat(request.body()).doesNotContain("geheim");
    }

    @Test
    @DisplayName("Ein Fehlerstatus der Gegenstelle gilt als gescheiterte Zustellung")
    void failsOnErrorStatus() {
        // Sonst gaelte die Meldung als zugestellt, obwohl sie niemanden erreicht hat --
        // und das Schweigen wuerde fuer ein gutes Zeichen gehalten.
        endpoint.respondWith(500, "kaputt");

        assertThatThrownBy(() -> transport.send(
                new ChannelConfig.Webhook(endpoint.url(), null, null), entry(Severity.CRITICAL)))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("Eine unerreichbare Gegenstelle gilt als gescheiterte Zustellung")
    void failsWhenUnreachable() {
        endpoint.close();

        assertThatThrownBy(() -> transport.send(
                new ChannelConfig.Webhook(endpoint.url(), null, null), entry(Severity.CRITICAL)))
                .isInstanceOf(NotificationException.class);
    }
}
