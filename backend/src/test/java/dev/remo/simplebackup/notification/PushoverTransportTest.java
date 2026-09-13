package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.secret.CredentialService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class PushoverTransportTest {

    private static final UUID ACCESS_ID = UUID.randomUUID();

    private FakeEndpoint endpoint;
    private PushoverTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        endpoint = new FakeEndpoint();

        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(ACCESS_ID))
                .thenReturn("{\"apiToken\":\"app-token\",\"userKey\":\"benutzer\"}");

        transport = new PushoverTransport(HttpClient.newHttpClient(), credentials, objectMapper,
                new NotificationProperties(Duration.ofSeconds(1), 10, endpoint.url()));
    }

    @AfterEach
    void tearDown() {
        endpoint.close();
    }

    private OutboxEntry entry(Severity severity) {
        var notification = new Notification("RUN_FAILED", severity, "Sicherung fehlgeschlagen",
                "NAS: fehlgeschlagen", null, null, Map.of());

        return new OutboxEntry(UUID.randomUUID(), notification, "{}");
    }

    private ChannelConfig.Pushover channel(boolean emergency) {
        return new ChannelConfig.Pushover(ACCESS_ID, null, emergency, null, null);
    }

    @Test
    @DisplayName("Kritische Meldungen verlangen eine Quittung")
    void criticalUsesEmergencyPriority() {
        // Der eigentliche Grund fuer Pushover: Ein fehlgeschlagenes Backup, das man im
        // Schlaf weggewischt hat, ist ein fehlgeschlagenes Backup, von dem niemand weiss.
        transport.send(channel(true), entry(Severity.CRITICAL));

        var fields = FakeEndpoint.formFields(endpoint.lastRequest().body());

        assertThat(fields.get("priority")).isEqualTo("2");
        assertThat(fields.get("retry")).isEqualTo("60");
        assertThat(fields.get("expire")).isEqualTo("3600");
        assertThat(fields.get("token")).isEqualTo("app-token");
        assertThat(fields.get("user")).isEqualTo("benutzer");
    }

    @Test
    @DisplayName("Ohne Quittierungspflicht bleibt es bei der lauten, aber stillen Stufe")
    void criticalWithoutEmergency() {
        transport.send(channel(false), entry(Severity.CRITICAL));

        var fields = FakeEndpoint.formFields(endpoint.lastRequest().body());

        assertThat(fields.get("priority")).isEqualTo("1");
        assertThat(fields).doesNotContainKey("retry");
    }

    @Test
    @DisplayName("Erfolgsmeldungen kommen ohne Aufdringlichkeit")
    void infoIsQuiet() {
        transport.send(channel(true), entry(Severity.INFO));

        assertThat(FakeEndpoint.formFields(endpoint.lastRequest().body()).get("priority")).isEqualTo("0");
    }

    @Test
    @DisplayName("Die Quittung wird aus der Antwort gelesen")
    void readsReceipt() {
        // Ueber die Quittung laesst sich spaeter abfragen, ob jemand den Alarm bestaetigt hat.
        endpoint.respondWith(200, "{\"status\":1,\"receipt\":\"r123\"}");

        assertThat(transport.send(channel(true), entry(Severity.CRITICAL))).isEqualTo("r123");
    }

    @Test
    @DisplayName("Ein Fehler des Dienstes gilt als gescheiterte Zustellung")
    void failsOnErrorStatus() {
        endpoint.respondWith(400, "{\"errors\":[\"application token is invalid\"]}");

        assertThatThrownBy(() -> transport.send(channel(true), entry(Severity.CRITICAL)))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("Ein unbrauchbarer Zugang wird als solcher gemeldet")
    void failsOnBrokenCredentials() {
        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(ACCESS_ID)).thenReturn("nur-ein-token");

        var broken = new PushoverTransport(HttpClient.newHttpClient(), credentials, objectMapper,
                new NotificationProperties(Duration.ofSeconds(1), 10, endpoint.url()));

        assertThatThrownBy(() -> broken.send(channel(true), entry(Severity.CRITICAL)))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("unbrauchbar");
    }
}
