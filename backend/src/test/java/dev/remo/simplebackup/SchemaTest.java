package dev.remo.simplebackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Prueft, dass die Flyway-Migrationen durchlaufen und das erzeugte Schema zu den
 * JPA-Entitaeten passt.
 *
 * <p>Die eigentliche Pruefung passiert bereits beim Hochfahren des Kontexts: Flyway wendet
 * die Migrationen an, anschliessend validiert Hibernate wegen {@code ddl-auto: validate}
 * jede Entitaet gegen die tatsaechlichen Tabellen. Eine Abweichung -- ein umbenanntes Feld,
 * ein vergessener Spaltentyp -- laesst den Kontext scheitern, statt erst im Betrieb
 * aufzufallen.
 */
class SchemaTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    /**
     * Eindeutig je Testlauf. Die Testdaten muessen kollisionsfrei bleiben, auch wenn die
     * Tests wiederholt gegen dieselbe Datenbank laufen -- gegen eine jedes Mal frisch
     * erzeugte Instanz waere ein nicht wiederholbarer Test nie aufgefallen.
     */
    private final String testRunId = "test-" + UUID.randomUUID();

    @AfterEach
    void removeTestData() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM backup_target WHERE name LIKE '" + testRunId + "%'");
            statement.executeUpdate("DELETE FROM retention_policy WHERE name LIKE '" + testRunId + "%'");
        }
    }

    @Test
    @DisplayName("Alle erwarteten Tabellen existieren nach der Migration")
    void createsAllTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (var connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }

        assertThat(tables).contains(
                "app_user", "audit_log", "credential",
                "backup_source", "source_credential",
                "backup_target", "target_credential",
                "retention_policy", "backup_plan", "plan_target",
                "backup_run", "run_step", "snapshot",
                "notification_channel", "notification_outbox",
                "spring_session", "spring_session_attributes");
    }

    @Test
    @DisplayName("Eine Aufbewahrungsregel, die nichts behaelt, wird von der Datenbank abgelehnt")
    void rejectsRetentionPolicyThatKeepsNothing() {
        // Die wichtigste Schutzregel des Schemas: Eine solche Regel wuerde beim ersten
        // Prune saemtliche Snapshots loeschen.
        assertThat(isRejected("""
                INSERT INTO retention_policy (name) VALUES ('%s-leer')
                """.formatted(testRunId))).isTrue();

        assertThat(isRejected("""
                INSERT INTO retention_policy (name, keep_daily) VALUES ('%s-taeglich', 7)
                """.formatted(testRunId))).isFalse();
    }

    @Test
    @DisplayName("Ein restic-Ziel ohne Repository-Passwort wird abgelehnt")
    void rejectsResticTargetWithoutPassword() {
        assertThat(isRejected("""
                INSERT INTO backup_target (name, type, mode, config)
                VALUES ('%s-ohne-passwort', 'LOCAL_PATH', 'RESTIC', '{"path": "/mnt/backup"}'::jsonb)
                """.formatted(testRunId))).isTrue();

        assertThat(isRejected("""
                INSERT INTO backup_target (name, type, mode, config)
                VALUES ('%s-spiegel', 'LOCAL_PATH', 'MIRROR', '{"path": "/mnt/spiegel"}'::jsonb)
                """.formatted(testRunId))).isFalse();
    }

    /**
     * Fuehrt die Anweisung in einer eigenen Verbindung aus.
     *
     * <p>Getrennte Verbindungen sind hier wesentlich: PostgreSQL bricht nach einem
     * Constraint-Verstoss die laufende Transaktion ab, sodass jede weitere Anweisung
     * darin ebenfalls scheitern wuerde -- und der Test das Falsche messen wuerde.
     *
     * @return true, wenn die Datenbank die Anweisung zurueckgewiesen hat
     */
    private boolean isRejected(String sql) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            return false;
        } catch (SQLException e) {
            return true;
        }
    }
}
