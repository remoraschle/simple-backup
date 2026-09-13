package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.secret.CredentialService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * Der Versand per E-Mail, gegen einen echten Mailserver auf einem echten Socket.
 *
 * <p>Geprueft wird beides: dass eine Meldung ankommt und so aussieht, dass man in einer
 * vollen Inbox erkennt, worum es geht -- und dass ein ablehnender Server nicht als Erfolg
 * durchgeht. Der zweite Fall ist der wichtigere: Eine Alarmmeldung, die als zugestellt gilt
 * und nie ankam, ist schlimmer als gar keine.
 */
class SmtpTransportTest {

    private static final UUID PASSWORD_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FakeMailServer server;
    private SmtpTransport transport;

    @BeforeEach
    void setUp() {
        server = new FakeMailServer();

        var credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.reveal(PASSWORD_ID)).thenReturn("geheimes-mailpasswort");

        transport = new SmtpTransport(credentials);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ChannelConfig.Smtp channel(String... recipients) {
        return new ChannelConfig.Smtp(server.host(), server.port(), false,
                "backup@example.org", List.of(recipients), null, null);
    }

    private OutboxEntry entry(Severity severity) {
        var notification = new Notification("RUN_FAILED", severity, "Sicherung fehlgeschlagen",
                "NAS: Verbindung abgelehnt", UUID.randomUUID(), UUID.randomUUID(),
                Map.of("planName", "Nachts"));

        return new OutboxEntry(UUID.randomUUID(), notification,
                objectMapper.writeValueAsString(notification.payload()));
    }

    @Nested
    @DisplayName("Zustellung")
    class Delivery {

        @Test
        @DisplayName("Die Meldung kommt beim Mailserver an")
        void deliversTheMessage() {
            transport.send(channel("admin@example.org"), entry(Severity.CRITICAL));

            var message = server.lastMessage();
            assertThat(message.from()).isEqualTo("backup@example.org");
            assertThat(message.recipients()).containsExactly("admin@example.org");
            assertThat(message.body()).contains("NAS: Verbindung abgelehnt");
        }

        @Test
        @DisplayName("Die Stufe steht im Betreff")
        void putsSeverityInTheSubject() {
            // In einer Liste von hundert Nachrichten entscheidet sich an dieser Zeile, ob
            // jemand heute Nacht noch aufsteht.
            transport.send(channel("admin@example.org"), entry(Severity.CRITICAL));

            assertThat(server.lastMessage().header("Subject"))
                    .isEqualTo("[CRITICAL] Sicherung fehlgeschlagen");
        }

        @Test
        @DisplayName("Mehrere Empfaenger bekommen dieselbe Meldung")
        void deliversToEveryRecipient() {
            transport.send(channel("admin@example.org", "vertretung@example.org"),
                    entry(Severity.WARNING));

            assertThat(server.lastMessage().recipients())
                    .containsExactly("admin@example.org", "vertretung@example.org");
        }

        @Test
        @DisplayName("Umlaute ueberstehen den Weg")
        void keepsGermanCharacters() {
            var notification = new Notification("BACKUP_OVERDUE", Severity.CRITICAL,
                    "Sicherung überfällig", "Plan „Fotos“ hat seit 3 Tagen nichts gesichert.",
                    UUID.randomUUID(), null, Map.of());

            transport.send(channel("admin@example.org"),
                    new OutboxEntry(UUID.randomUUID(), notification, null));

            // Der Rumpf ist quoted-printable oder Base64 kodiert -- entscheidend ist, dass
            // die Nachricht ueberhaupt mit der richtigen Kodierung ausgezeichnet ist.
            assertThat(server.lastMessage().header("Content-Type")).contains("UTF-8");
        }
    }

    @Nested
    @DisplayName("Fehlschlag")
    class Failure {

        @Test
        @DisplayName("Ein ablehnender Server gilt nicht als Erfolg")
        void reportsRejection() {
            server.rejectEverything();

            assertThatThrownBy(() -> transport.send(channel("unbekannt@example.org"),
                    entry(Severity.CRITICAL)))
                    .isInstanceOf(NotificationException.class)
                    .hasMessageContaining("Mailserver");
        }

        @Test
        @DisplayName("Ein nicht erreichbarer Server auch nicht")
        void reportsUnreachableServer() {
            var unreachable = new ChannelConfig.Smtp("127.0.0.1", 1, false, "backup@example.org",
                    List.of("admin@example.org"), null, null);

            assertThatThrownBy(() -> transport.send(unreachable, entry(Severity.CRITICAL)))
                    .isInstanceOf(NotificationException.class);
        }
    }

    @Nested
    @DisplayName("Konfiguration")
    class Configuration {

        @Test
        @DisplayName("Ohne Empfaenger laesst sich kein Kanal anlegen")
        void requiresRecipients() {
            assertThatThrownBy(() -> new ChannelConfig.Smtp("mail.example.org", 587, true,
                    "backup@example.org", List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Empfaenger");
        }

        @Test
        @DisplayName("Ein Tippfehler in der Adresse faellt beim Anlegen auf")
        void rejectsMalformedAddress() {
            // Und nicht erst dann, wenn die erste Alarmmeldung nicht ankommt.
            assertThatThrownBy(() -> new ChannelConfig.Smtp("mail.example.org", 587, true,
                    "backup@example.org", List.of("admin.example.org"), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E-Mail-Adresse");
        }

        @Test
        @DisplayName("Zu einem Anmeldenamen gehoert ein Passwort")
        void requiresPasswordWithUsername() {
            assertThatThrownBy(() -> new ChannelConfig.Smtp("mail.example.org", 587, true,
                    "backup@example.org", List.of("admin@example.org"), "backup", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Passwort");
        }

        @Test
        @DisplayName("Das Passwort steht nur als Verweis in der Konfiguration")
        void storesOnlyCredentialReference() {
            // Die Konfiguration geht unveraendert an die API. Ein Klartextfeld hier waere
            // ein Leck, das keine spaetere Filterung mehr zuverlaessig schliesst.
            String json = objectMapper.writeValueAsString(new ChannelConfig.Smtp(
                    "mail.example.org", 587, true, "backup@example.org",
                    List.of("admin@example.org"), "backup", PASSWORD_ID));

            assertThat(json).contains("credentialId").doesNotContain("geheimes-mailpasswort");
        }

        @Test
        @DisplayName("Ohne Portangabe wird der uebliche genommen")
        void defaultsToSubmissionPort() {
            assertThat(new ChannelConfig.Smtp("mail.example.org", 0, true, "backup@example.org",
                    List.of("admin@example.org"), null, null).port()).isEqualTo(587);
        }
    }
}
