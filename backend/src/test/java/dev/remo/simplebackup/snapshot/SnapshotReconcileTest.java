package dev.remo.simplebackup.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Der Abgleich zwischen Repository und Verzeichnis.
 *
 * <p>Ein Repository enthaelt nicht nur, was diese Anwendung hineingeschrieben hat: Snapshots
 * eines geloeschten Plans, eines anderen Servers oder ein Handgriff auf der Kommandozeile
 * liegen dort genauso. Im Verzeichnis haengt dagegen jeder Eintrag an einem Plan. Was sich
 * keinem zuordnen laesst, muss deshalb uebergangen werden -- und darf den Abgleich nicht
 * scheitern lassen, sonst ist nach einem solchen Fund gar keiner mehr moeglich.
 */
class SnapshotReconcileTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    private SnapshotRepository repository;
    private SnapshotService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(SnapshotRepository.class);
        Mockito.when(repository.findAllByTargetIdOrderBySnapshotTimeDesc(TARGET))
                .thenReturn(new ArrayList<>());

        service = new SnapshotService(repository);
    }

    private static SnapshotService.RepositorySnapshot snapshot(String id, UUID planId) {
        return new SnapshotService.RepositorySnapshot(id, Instant.parse("2026-03-01T02:00:00Z"),
                null, planId);
    }

    private List<Snapshot> saved() {
        var captor = ArgumentCaptor.forClass(Snapshot.class);
        Mockito.verify(repository, Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("Ein Snapshot mit erkanntem Plan kommt ins Verzeichnis")
    void recordsAttributedSnapshot() {
        int recorded = service.reconcile(TARGET, null, List.of(snapshot("abc123", PLAN)));

        assertThat(recorded).isEqualTo(1);
        assertThat(saved()).singleElement()
                .satisfies(entry -> assertThat(entry.getPlanId()).isEqualTo(PLAN));
    }

    @Test
    @DisplayName("Ein fremder Snapshot wird uebergangen statt den Abgleich zu sprengen")
    void ignoresForeignSnapshot() {
        // Frueher endete dieser Fall in einer Verletzung der Nichtnull-Bedingung auf
        // plan_id -- und damit in einem Serverfehler fuer den ganzen Abgleich.
        int recorded = service.reconcile(TARGET, null, List.of(snapshot("fremd", null)));

        assertThat(recorded).isZero();
        assertThat(saved()).isEmpty();
    }

    @Test
    @DisplayName("Fremde Snapshots halten die eigenen nicht auf")
    void recordsOwnDespiteForeign() {
        int recorded = service.reconcile(TARGET, null,
                List.of(snapshot("fremd", null), snapshot("eigen", PLAN)));

        assertThat(recorded).isEqualTo(1);
        assertThat(saved()).singleElement()
                .satisfies(entry -> assertThat(entry.getExternalId()).isEqualTo("eigen"));
    }

    @Test
    @DisplayName("Beim Abgleich eines einzelnen Plans gilt dessen Kennung fuer alle")
    void fallsBackToTheGivenPlan() {
        // Dann hat restic bereits nach Host und Tag dieses Plans gefiltert.
        int recorded = service.reconcile(TARGET, PLAN, List.of(snapshot("abc123", null)));

        assertThat(recorded).isEqualTo(1);
        assertThat(saved()).singleElement()
                .satisfies(entry -> assertThat(entry.getPlanId()).isEqualTo(PLAN));
    }

    @Test
    @DisplayName("Was im Repository fehlt, fliegt aus dem Verzeichnis")
    void dropsVanishedSnapshots() {
        var vanished = new Snapshot(null, PLAN, TARGET, "weg", null, Instant.now());
        Mockito.when(repository.findAllByTargetIdOrderBySnapshotTimeDesc(TARGET))
                .thenReturn(new ArrayList<>(List.of(vanished)));

        service.reconcile(TARGET, null, List.of(snapshot("noch-da", PLAN)));

        Mockito.verify(repository).delete(vanished);
    }
}
