package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Das Wiederholen ist der Kern des Postausgangs: Eine Alarmmeldung, die selbst verloren
 * geht, ist schlimmer als keine -- man haelt das Schweigen fuer ein gutes Zeichen.
 */
class OutboxEntryTest {

    private OutboxEntry entry() {
        return new OutboxEntry(UUID.randomUUID(),
                new Notification("RUN_FAILED", Severity.CRITICAL, "Titel", "Text", null, null, Map.of()),
                "{}");
    }

    @Test
    @DisplayName("Der Abstand zwischen den Versuchen waechst")
    void backoffGrows() {
        // Ein dauerhaft kaputter Kanal soll nicht im Sekundentakt angeklopft werden.
        assertThat(OutboxEntry.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxEntry.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxEntry.backoff(3)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("Der Abstand ist bei einer Stunde gedeckelt")
    void backoffIsCapped() {
        // Sonst laege der naechste Versuch nach wenigen Fehlschlaegen in der uebernaechsten
        // Woche -- und der Alarm kaeme nie.
        assertThat(OutboxEntry.backoff(20)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Nach einem Fehlschlag steht ein neuer Versuch an")
    void staysPendingAfterFailure() {
        OutboxEntry entry = entry();

        entry.markFailed("Netz weg");

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).isEqualTo("Netz weg");
        assertThat(entry.getNextAttemptAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("Erschoepfte Versuche werden aufgegeben, aber nicht verschwiegen")
    void abandonsAfterMaxAttempts() {
        OutboxEntry entry = entry();

        for (int attempt = 0; attempt < OutboxEntry.MAX_ATTEMPTS; attempt++) {
            entry.markFailed("Netz weg");
        }

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.ABANDONED);
        // Der Eintrag bleibt mit seinem letzten Fehler stehen, damit es auffaellt.
        assertThat(entry.getLastError()).isEqualTo("Netz weg");
    }

    @Test
    @DisplayName("Ein erfolgreicher Versand loescht den letzten Fehler")
    void sendingClearsError() {
        OutboxEntry entry = entry();
        entry.markFailed("Netz weg");

        entry.markSent("r123");

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry.getLastError()).isNull();
        assertThat(entry.getExternalReceipt()).isEqualTo("r123");
        assertThat(entry.getSentAt()).isNotNull();
    }
}
