package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews;
import dev.remo.simplebackup.catalog.MissedRunPolicy;
import dev.remo.simplebackup.catalog.NotifyOn;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Die Kennzahlen, auf die eine Ueberwachung Alarmregeln stuetzt.
 *
 * <p>Geprueft wird vor allem das Alter der letzten erfolgreichen Sicherung. Es ist die
 * einzige Zahl, die den gefaehrlichen Fall abdeckt -- den Plan, der stillschweigend gar
 * nicht mehr laeuft und deshalb auch keine Fehlschlaege mehr erzeugt.
 */
class BackupMetricsTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");
    private static final UUID PLAN_ID = UUID.randomUUID();

    private CatalogService catalog;
    private RunRepository runs;
    private MeterRegistry registry;
    private BackupMetrics metrics;

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(CatalogService.class);
        runs = Mockito.mock(RunRepository.class);
        registry = new SimpleMeterRegistry();

        Mockito.when(catalog.listTargets()).thenReturn(List.of());
        Mockito.when(runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        Mockito.when(runs.findFirstByPlanIdAndFinishedAtNotNullOrderByFinishedAtDesc(any()))
                .thenReturn(Optional.empty());

        metrics = new BackupMetrics(catalog, runs, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CatalogViews.PlanView plan(Instant createdAt, Integer expectedIntervalMinutes) {
        return new CatalogViews.PlanView(PLAN_ID, "Fotos", null, UUID.randomUUID(), "Quelle",
                List.of(), null, "0 0 2 * * *", "Europe/Zurich", true, 120, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, expectedIntervalMinutes, null, null, null,
                createdAt);
    }

    private void lastSuccess(Instant finishedAt) {
        var run = Mockito.mock(BackupRun.class);
        Mockito.when(run.getFinishedAt()).thenReturn(finishedAt);
        Mockito.when(runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(eq(PLAN_ID), any()))
                .thenReturn(Optional.of(run));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("Das Alter zaehlt ab der letzten erfolgreichen Sicherung")
    void reportsAgeSinceLastSuccess() {
        Mockito.when(catalog.listPlans()).thenReturn(List.of(plan(NOW.minus(Duration.ofDays(30)), null)));
        lastSuccess(NOW.minus(Duration.ofHours(6)));

        metrics.refresh();

        assertThat(gauge("simplebackup.plan.last.success.age")).isEqualTo(6 * 3600);
        assertThat(gauge("simplebackup.plan.last.success.timestamp"))
                .isEqualTo(NOW.minus(Duration.ofHours(6)).getEpochSecond());
    }

    @Test
    @DisplayName("Ohne jede erfolgreiche Sicherung zaehlt das Alter ab Anlage des Plans")
    void reportsAgeSincePlanCreation() {
        // Sonst waere ausgerechnet der nie gelaufene Plan der einzige, ueber den die
        // Ueberwachung schweigt.
        Mockito.when(catalog.listPlans()).thenReturn(List.of(plan(NOW.minus(Duration.ofDays(3)), null)));

        metrics.refresh();

        assertThat(gauge("simplebackup.plan.last.success.age")).isEqualTo(3 * 86400);
        assertThat(gauge("simplebackup.plan.last.success.timestamp")).isZero();
    }

    @Test
    @DisplayName("Der erwartete Abstand kommt als eigene Kennzahl mit")
    void reportsExpectedInterval() {
        // Damit die Alarmregel keinen fest verdrahteten Schwellwert je Plan braucht.
        Mockito.when(catalog.listPlans()).thenReturn(List.of(plan(NOW, 1440)));

        metrics.refresh();

        assertThat(gauge("simplebackup.plan.expected.interval")).isEqualTo(86400);
    }

    @Test
    @DisplayName("Ohne erwarteten Abstand steht dort null statt einer geratenen Zahl")
    void reportsZeroWithoutExpectation() {
        Mockito.when(catalog.listPlans()).thenReturn(List.of(plan(NOW, null)));

        metrics.refresh();

        assertThat(gauge("simplebackup.plan.expected.interval")).isZero();
    }

    @Test
    @DisplayName("Ein geloeschter Plan verschwindet aus den Kennzahlen")
    void forgetsDeletedPlans() {
        // Bliebe er stehen, alterte seine letzte Sicherung ewig weiter und loeste
        // frueher oder spaeter Alarm aus -- fuer einen Plan, den es nicht mehr gibt.
        Mockito.when(catalog.listPlans()).thenReturn(List.of(plan(NOW, null)));
        metrics.refresh();

        Mockito.when(catalog.listPlans()).thenReturn(List.of());
        metrics.refresh();

        assertThat(registry.find("simplebackup.plan.last.success.age").gauges()).isEmpty();
    }

    @Test
    @DisplayName("Abgeschlossene Laeufe werden nach Ausgang gezaehlt")
    void countsRunsByStatus() {
        Mockito.when(catalog.planNames(any())).thenReturn(java.util.Map.of(PLAN_ID, "Fotos"));

        metrics.runFinished(PLAN_ID, RunStatus.SUCCESS);
        metrics.runFinished(PLAN_ID, RunStatus.SUCCESS);
        metrics.runFinished(PLAN_ID, RunStatus.PARTIAL);

        assertThat(registry.get("simplebackup.runs").tag("status", "SUCCESS").counter().count())
                .isEqualTo(2);
        // Getrennt gezaehlt: Ein Teilerfolg sieht gruen aus und ist es nicht.
        assertThat(registry.get("simplebackup.runs").tag("status", "PARTIAL").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Eine Panne beim Erheben bleibt eine Panne beim Erheben")
    void survivesFailure() {
        // Eine unerreichbare Datenbank darf nicht den Zeitplaner mitreissen, der in
        // demselben Ausfuehrer haengt.
        Mockito.when(catalog.listPlans()).thenThrow(new IllegalStateException("Datenbank weg"));

        metrics.refresh();

        assertThat(registry.find("simplebackup.plan.last.success.age").gauges()).isEmpty();
    }
}
