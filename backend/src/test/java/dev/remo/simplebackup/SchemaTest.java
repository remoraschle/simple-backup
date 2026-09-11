package dev.remo.simplebackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
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
    void rejectsRetentionPolicyThatKeepsNothing() throws Exception {
        // Die wichtigste Schutzregel des Schemas: Eine solche Regel wuerde beim ersten
        // Prune saemtliche Snapshots loeschen.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {

            assertThat(catchInsert(statement, """
                    INSERT INTO retention_policy (name) VALUES ('behaelt-nichts')
                    """)).isTrue();

            assertThat(catchInsert(statement, """
                    INSERT INTO retention_policy (name, keep_daily) VALUES ('behaelt-sieben-tage', 7)
                    """)).isFalse();
        }
    }

    @Test
    @DisplayName("Ein restic-Ziel ohne Repository-Passwort wird abgelehnt")
    void rejectsResticTargetWithoutPassword() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {

            assertThat(catchInsert(statement, """
                    INSERT INTO backup_target (name, type, mode, config)
                    VALUES ('ziel-ohne-passwort', 'LOCAL_PATH', 'RESTIC', '{"path": "/mnt/backup"}'::jsonb)
                    """)).isTrue();

            assertThat(catchInsert(statement, """
                    INSERT INTO backup_target (name, type, mode, config)
                    VALUES ('spiegel-braucht-keines', 'LOCAL_PATH', 'MIRROR', '{"path": "/mnt/spiegel"}'::jsonb)
                    """)).isFalse();
        }
    }

    /** @return true, wenn die Datenbank die Anweisung zurueckgewiesen hat */
    private static boolean catchInsert(java.sql.Statement statement, String sql) {
        try {
            statement.executeUpdate(sql);
            return false;
        } catch (java.sql.SQLException e) {
            return true;
        }
    }
}
