package dev.remo.simplebackup.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.IntegrationTestBase;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Die Uebernahme faelliger Plaene gegen eine echte Datenbank.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} laesst sich nur so pruefen: Es ist reines
 * Datenbankverhalten, das keine Attrappe nachbilden koennte.
 */
@Import(PlanClaimingTest.TestMounts.class)
class PlanClaimingTest extends IntegrationTestBase {

    @TestConfiguration
    static class TestMounts {
        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount("/srv/daten", "/sources/daten", true, false),
                    new VolumeMount("/mnt/nas", "/mnt/nas", false, false)));
        }
    }

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PlanRepository plans;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private TargetRepository targets;

    @BeforeEach
    void cleanUp() {
        plans.deleteAll();
        sources.deleteAll();
        targets.deleteAll();
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Legt einen Plan an, dessen naechster Termin bereits vorbei ist. */
    private UUID createDuePlan() {
        var source = catalog.createSource(new CatalogRequests.SaveSource(unique("quelle"), null,
                new SourceConfig.LocalPath(List.of("/sources/daten"), List.of(), false)));

        var target = catalog.createTarget(new CatalogRequests.SaveTarget(unique("ziel"), null,
                TargetMode.RESTIC, new TargetConfig.LocalPath("/mnt/nas/backup", UUID.randomUUID()), true));

        var plan = catalog.createPlan(new CatalogRequests.SavePlan(unique("plan"), null, source.id(),
                List.of(target.id()), null, "0 0 2 * * *", "Europe/Zurich", true, 360, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, null));

        var entity = plans.findById(plan.id()).orElseThrow();
        entity.scheduleNext(Instant.now().minusSeconds(60));
        plans.save(entity);

        return plan.id();
    }

    @Test
    @DisplayName("Ein faelliger Plan wird uebernommen")
    void claimsDuePlan() {
        UUID planId = createDuePlan();

        assertThat(catalog.claimDuePlans(10)).containsExactly(planId);
    }

    @Test
    @DisplayName("Ein noch nicht faelliger Plan wird nicht uebernommen")
    void ignoresFuturePlan() {
        UUID planId = createDuePlan();
        var plan = plans.findById(planId).orElseThrow();
        plan.scheduleNext(Instant.now().plusSeconds(3600));
        plans.save(plan);

        assertThat(catalog.claimDuePlans(10)).isEmpty();
    }

    @Test
    @DisplayName("Der naechste Termin wird sofort gesetzt, nicht erst nach dem Lauf")
    void advancesScheduleImmediately() {
        // Sonst faende der naechste Durchgang -- dreissig Sekunden spaeter -- denselben Plan
        // erneut und startete ihn ein zweites Mal.
        UUID planId = createDuePlan();

        catalog.claimDuePlans(10);

        var plan = plans.findById(planId).orElseThrow();
        assertThat(plan.getNextRunAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Ein zweiter Durchgang findet denselben Plan nicht noch einmal")
    void doesNotClaimTwice() {
        createDuePlan();

        assertThat(catalog.claimDuePlans(10)).hasSize(1);
        assertThat(catalog.claimDuePlans(10)).isEmpty();
    }

    @Test
    @DisplayName("Ein abgeschalteter Plan wird nie uebernommen")
    void ignoresDisabledPlan() {
        UUID planId = createDuePlan();
        var view = catalog.getPlan(planId);

        // Ueber den regulaeren Weg abschalten statt die Entitaet zu verbiegen -- so wird
        // zugleich geprueft, dass das Abschalten den Termin loescht.
        catalog.updatePlan(planId, new CatalogRequests.SavePlan(view.name(), null, view.sourceId(),
                view.targets().stream().map(CatalogViews.TargetReference::id).toList(), null,
                view.cronExpression(), view.timezone(), false, 360, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, null));

        assertThat(catalog.getPlan(planId).nextRunAt()).isNull();
        assertThat(catalog.claimDuePlans(10)).isEmpty();
    }

    @Test
    @DisplayName("Die Anzahl je Durchgang ist begrenzt")
    void respectsBatchLimit() {
        // Nach einem laengeren Stillstand koennten sonst hunderte Plaene gleichzeitig
        // starten.
        createDuePlan();
        createDuePlan();
        createDuePlan();

        assertThat(catalog.claimDuePlans(2)).hasSize(2);
    }

    @Test
    @DisplayName("Nach einem Stillstand werden vergangene Termine neu berechnet")
    void reschedulesAfterDowntime() {
        UUID planId = createDuePlan();
        var plan = plans.findById(planId).orElseThrow();
        plan.scheduleNext(Instant.now().minusSeconds(86_400 * 5));
        plans.save(plan);

        int adjusted = catalog.rescheduleAfterDowntime();

        assertThat(adjusted).isEqualTo(1);
        assertThat(plans.findById(planId).orElseThrow().getNextRunAt()).isAfter(Instant.now());
    }
}
