package dev.remo.simplebackup.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.remo.simplebackup.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Das Pruefprotokoll darf nie die Aktion verhindern, die es protokolliert.
 *
 * <p>Dieser Test existiert wegen eines Fehlers, den kein Einzeltest fand: Das Detailfeld
 * liegt als JSONB in der Datenbank, und ein Pfad oder ein „100%" ist kein gueltiges JSON.
 * Jede Anfrage mit einem solchen Detail endete mit einem Serverfehler -- die Aktion
 * scheiterte am Protokoll ueber die Aktion.
 */
class AuditServiceTest extends IntegrationTestBase {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository entries;

    private String record(String detail) {
        String action = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        audit.record("admin", action, "backup_target", UUID.randomUUID().toString(), detail);
        return action;
    }

    private String detailOf(String action) {
        return entries.findAll().stream()
                .filter(entry -> entry.getAction().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Eintrag fuer " + action))
                .getDetail();
    }

    @Test
    @DisplayName("Ein Pfad im Detailfeld ist kein Grund zu scheitern")
    void acceptsAPlainPath() {
        String action = record("/mnt/nas/wiederhergestellt");

        assertThat(detailOf(action)).contains("/mnt/nas/wiederhergestellt");
    }

    @Test
    @DisplayName("Auch Prozentzeichen und Anfuehrungszeichen gehen durch")
    void acceptsAwkwardCharacters() {
        String action = record("100% von \"allem\"");

        assertThat(detailOf(action)).contains("100%");
    }

    @Test
    @DisplayName("Ohne Detail bleibt das Feld leer")
    void acceptsNoDetail() {
        assertThat(detailOf(record(null))).isNull();
    }

    @Test
    @DisplayName("Ein zu langer oder unmoeglicher Eintrag reisst nichts mit")
    void neverThrows() {
        // Ein unvollstaendiges Pruefprotokoll ist schlecht, eine Anwendung, die deswegen
        // nichts mehr tut, ist schlechter.
        assertThatCode(() -> audit.record(null, null, null, null, "x")).doesNotThrowAnyException();
    }
}
