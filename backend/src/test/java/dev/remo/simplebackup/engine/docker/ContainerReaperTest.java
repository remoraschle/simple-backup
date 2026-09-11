package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ContainerReaperTest {

    private FakeDockerApi api;
    private ContainerReaper reaper;

    @BeforeEach
    void setUp() {
        api = new FakeDockerApi();
        var properties = new DockerProperties(api.baseUrl(), "v1.51", Duration.ofSeconds(2),
                Duration.ofSeconds(5), null, null);
        reaper = new ContainerReaper(new DockerApiClient(properties, new ObjectMapper()));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    private void containersFound(String json) {
        api.respond("/containers/json", 200, json);
    }

    private boolean wasRemoved(String containerId) {
        return api.requests().stream().anyMatch(recorded ->
                recorded.method().equals("DELETE") && recorded.path().equals("/containers/" + containerId));
    }

    @Test
    @DisplayName("Ein noch laufender, bekannter Container wird zum Wiederanhaengen gemeldet")
    void reportsRunningKnownContainer() {
        // Der entscheidende Fall: Ohne ihn waere ein vierstuendiges Backup durch einen
        // Neustart des Backends verloren.
        containersFound("""
                [{"Id":"aaa111","State":"running",
                  "Labels":{"simple-backup.managed-by":"simple-backup",
                            "simple-backup.execution-id":"lauf-1"}}]""");

        var adoptable = reaper.reap(Set.of("lauf-1"));

        assertThat(adoptable).singleElement().satisfies(container -> {
            assertThat(container.containerId()).isEqualTo("aaa111");
            assertThat(container.executionId()).isEqualTo("lauf-1");
            assertThat(container.running()).isTrue();
        });
        assertThat(wasRemoved("aaa111")).isFalse();
    }

    @Test
    @DisplayName("Ein zwischenzeitlich beendeter, bekannter Container bleibt zur Auswertung erhalten")
    void keepsFinishedKnownContainerForEvaluation() {
        // Genau deshalb ist AutoRemove abgeschaltet: Ein automatisch entfernter Container
        // haette Rueckgabewert und Log mitgenommen.
        containersFound("""
                [{"Id":"bbb222","State":"exited",
                  "Labels":{"simple-backup.managed-by":"simple-backup",
                            "simple-backup.execution-id":"lauf-2"}}]""");

        var adoptable = reaper.reap(Set.of("lauf-2"));

        assertThat(adoptable).singleElement().satisfies(container ->
                assertThat(container.running()).isFalse());
        assertThat(wasRemoved("bbb222")).isFalse();
    }

    @Test
    @DisplayName("Ein verwaister Container wird entfernt")
    void removesOrphanedContainer() {
        // Sonst sammeln sich mit jedem Neustart Leichen an.
        containersFound("""
                [{"Id":"ccc333","State":"exited",
                  "Labels":{"simple-backup.managed-by":"simple-backup",
                            "simple-backup.execution-id":"laengst-vergessen"}}]""");

        var adoptable = reaper.reap(Set.of("lauf-1"));

        assertThat(adoptable).isEmpty();
        assertThat(wasRemoved("ccc333")).isTrue();
    }

    @Test
    @DisplayName("Ein Container ohne Schritt-Kennung wird entfernt")
    void removesContainerWithoutExecutionLabel() {
        containersFound("""
                [{"Id":"ddd444","State":"exited",
                  "Labels":{"simple-backup.managed-by":"simple-backup"}}]""");

        assertThat(reaper.reap(Set.of("lauf-1"))).isEmpty();
        assertThat(wasRemoved("ddd444")).isTrue();
    }

    @Test
    @DisplayName("Bekannte und verwaiste Container werden im selben Durchgang getrennt behandelt")
    void separatesKnownFromOrphaned() {
        containersFound("""
                [{"Id":"aaa111","State":"running",
                  "Labels":{"simple-backup.managed-by":"simple-backup","simple-backup.execution-id":"lauf-1"}},
                 {"Id":"ccc333","State":"exited",
                  "Labels":{"simple-backup.managed-by":"simple-backup","simple-backup.execution-id":"weg"}},
                 {"Id":"eee555","State":"running",
                  "Labels":{"simple-backup.managed-by":"simple-backup","simple-backup.execution-id":"lauf-2"}}]""");

        var adoptable = reaper.reap(Set.of("lauf-1", "lauf-2"));

        assertThat(adoptable).extracting(AdoptableContainer::containerId)
                .containsExactlyInAnyOrder("aaa111", "eee555");
        assertThat(wasRemoved("ccc333")).isTrue();
        assertThat(wasRemoved("aaa111")).isFalse();
        assertThat(wasRemoved("eee555")).isFalse();
    }

    @Test
    @DisplayName("Ohne vorgefundene Container passiert nichts")
    void doesNothingWhenNoContainersExist() {
        containersFound("[]");

        assertThat(reaper.reap(Set.of("lauf-1"))).isEmpty();
        assertThat(api.requests()).noneMatch(recorded -> recorded.method().equals("DELETE"));
    }

    @Test
    @DisplayName("Ein nicht entfernbarer Container verhindert den Start nicht")
    void survivesFailedRemoval() {
        // Ein Container, der bleibt, ist ein Ärgernis. Ein Backend, das deshalb nicht
        // hochkommt, waere ein Ausfall.
        containersFound("""
                [{"Id":"fff666","State":"exited",
                  "Labels":{"simple-backup.managed-by":"simple-backup","simple-backup.execution-id":"weg"}}]""");
        api.respond("/containers/fff666", 500, """
                {"message":"device or resource busy"}""");

        assertThat(reaper.reap(Set.of())).isEmpty();
    }
}
