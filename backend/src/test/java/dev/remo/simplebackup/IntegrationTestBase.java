package dev.remo.simplebackup;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Basis fuer Tests, die eine echte PostgreSQL-Instanz brauchen.
 *
 * <p>Standardmaessig wird ein Container gestartet. Ist {@code SIMPLEBACKUP_TEST_DB_URL}
 * gesetzt, wird stattdessen diese Datenbank verwendet -- das erlaubt es, die Tests auch dort
 * auszufuehren, wo kein Docker-Daemon zur Verfuegung steht, und spart bei der lokalen
 * Entwicklung das wiederholte Hochfahren eines Containers.
 *
 * <p>Eine eingebettete Datenbank waere keine Alternative: Das Schema nutzt JSONB,
 * Teilindizes und {@code gen_random_uuid()}. Gegen etwas anderes als PostgreSQL zu testen
 * hiesse, das Falsche zu testen.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static PostgreSQLContainer container;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("SIMPLEBACKUP_TEST_DB_URL");

        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> envOrDefault("SIMPLEBACKUP_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> envOrDefault("SIMPLEBACKUP_TEST_DB_PASSWORD"));
            return;
        }

        if (container == null) {
            container = new PostgreSQLContainer("postgres:18-alpine");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String envOrDefault(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? "simplebackup" : value;
    }
}
