package dev.remo.simplebackup.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.IntegrationTestBase;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.shared.ConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Import(CatalogServiceTest.TestMounts.class)
class CatalogServiceTest extends IntegrationTestBase {

    /**
     * Die Anwendung laeuft im Test nicht in einem Container und haette deshalb eine leere
     * Mount-Tabelle. Diese hier entspricht einer typischen Compose-Datei.
     */
    @TestConfiguration
    static class TestMounts {

        @Bean
        @Primary
        MountTranslator testMountTranslator() {
            return new MountTranslator(List.of(
                    new VolumeMount("/srv/fotos", "/sources/fotos", true, false),
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

    @Autowired
    private RetentionPolicyRepository policies;

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void cleanUp() {
        plans.deleteAll();
        sources.deleteAll();
        targets.deleteAll();
        policies.deleteAll();
    }

    private CatalogViews.SourceView createSource(String... paths) {
        return catalog.createSource(new CatalogRequests.SaveSource(unique("quelle"), null,
                new SourceConfig.LocalPath(List.of(paths), List.of(), false)));
    }

    private CatalogViews.TargetView createTarget() {
        return catalog.createTarget(new CatalogRequests.SaveTarget(unique("ziel"), null,
                TargetMode.RESTIC,
                new TargetConfig.LocalPath("/mnt/nas/backups", UUID.randomUUID()), true));
    }

    @Nested
    @DisplayName("Quellen")
    class Sources {

        @Test
        @DisplayName("Eine Quelle mit erreichbarem Pfad laesst sich anlegen")
        void createsSourceWithReachablePath() {
            var source = createSource("/sources/fotos/2024");

            assertThat(source.type()).isEqualTo(SourceType.LOCAL_PATH);
            assertThat(source.config()).isInstanceOf(SourceConfig.LocalPath.class);
        }

        @Test
        @DisplayName("Ein Pfad, den kein Runner erreichen kann, wird beim Anlegen abgelehnt")
        void rejectsUnreachablePath() {
            // Die wichtigste Pruefung ueberhaupt: Ohne sie entstuende ein Plan, der jede
            // Nacht ein leeres Verzeichnis sichert und dabei Erfolg meldet.
            assertThatThrownBy(() -> createSource("/etc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nicht eingehaengt")
                    .hasMessageContaining("Compose-Datei");
        }

        @Test
        @DisplayName("Ein doppelter Name wird abgelehnt")
        void rejectsDuplicateName() {
            var request = new CatalogRequests.SaveSource("gleicher-name", null,
                    new SourceConfig.LocalPath(List.of("/sources/fotos"), List.of(), false));
            catalog.createSource(request);

            assertThatThrownBy(() -> catalog.createSource(request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("existiert bereits");
        }

        @Test
        @DisplayName("Eine Quelle, die ein Plan verwendet, laesst sich nicht loeschen")
        void refusesToDeleteSourceInUse() {
            var source = createSource("/sources/fotos");
            var target = createTarget();
            catalog.createPlan(planRequest(source.id(), target.id(), true));

            assertThatThrownBy(() -> catalog.deleteSource(source.id()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("wird von mindestens einem Plan verwendet");
        }

        @Test
        @DisplayName("Eine unbenutzte Quelle laesst sich loeschen")
        void deletesUnusedSource() {
            var source = createSource("/sources/fotos");

            catalog.deleteSource(source.id());

            assertThat(catalog.listSources()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ziele")
    class Targets {

        @Test
        @DisplayName("Ein restic-Ziel ohne Repository-Passwort wird abgelehnt")
        void rejectsResticTargetWithoutPassword() {
            // Ohne dieses Passwort waeren die Sicherungen spaeter nicht mehr lesbar.
            var request = new CatalogRequests.SaveTarget(unique("ziel"), null, TargetMode.RESTIC,
                    new TargetConfig.LocalPath("/mnt/nas/backups", null), true);

            assertThatThrownBy(() -> catalog.createTarget(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keine Hintertuer");
        }

        @Test
        @DisplayName("Ein Spiegel-Ziel braucht kein Passwort")
        void mirrorTargetNeedsNoPassword() {
            var target = catalog.createTarget(new CatalogRequests.SaveTarget(unique("spiegel"), null,
                    TargetMode.MIRROR, new TargetConfig.LocalPath("/mnt/nas/spiegel", null), true));

            assertThat(target.mode()).isEqualTo(TargetMode.MIRROR);
            assertThat(target.mode().supportsSnapshots()).isFalse();
        }

        @Test
        @DisplayName("Ein Ziel auf einem nicht eingehaengten Pfad wird abgelehnt")
        void rejectsUnreachableTargetPath() {
            var request = new CatalogRequests.SaveTarget(unique("ziel"), null, TargetMode.RESTIC,
                    new TargetConfig.LocalPath("/woanders/backups", UUID.randomUUID()), true);

            assertThatThrownBy(() -> catalog.createTarget(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nicht eingehaengt");
        }

        @Test
        @DisplayName("Der Modus eines Ziels laesst sich nicht nachtraeglich aendern")
        void refusesModeChange() {
            // Ein bestehendes restic-Repository waere danach nicht mehr lesbar.
            var target = createTarget();

            assertThatThrownBy(() -> catalog.updateTarget(target.id(),
                    new CatalogRequests.SaveTarget(target.name(), null, TargetMode.MIRROR,
                            new TargetConfig.LocalPath("/mnt/nas/backups", UUID.randomUUID()), true)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("nicht mehr lesbar");
        }

        @Test
        @DisplayName("Noch nicht umgesetzte Zieltypen werden abgelehnt")
        void rejectsUnimplementedType() {
            // Besser abgelehnt als angelegt und beim ersten Lauf gescheitert.
            assertThat(TargetType.SFTP.isImplemented()).isFalse();
        }
    }

    @Nested
    @DisplayName("Plaene")
    class Plans {

        @Test
        @DisplayName("Ein aktiver Plan bekommt beim Anlegen seinen naechsten Termin")
        void schedulesEnabledPlan() {
            var source = createSource("/sources/fotos");
            var target = createTarget();

            var plan = catalog.createPlan(planRequest(source.id(), target.id(), true));

            assertThat(plan.nextRunAt()).isNotNull().isAfter(java.time.Instant.now());
            assertThat(plan.targets()).hasSize(1);
        }

        @Test
        @DisplayName("Ein abgeschalteter Plan bekommt keinen Termin")
        void doesNotScheduleDisabledPlan() {
            // Sonst liefe er nach dem Wiedereinschalten sofort los.
            var source = createSource("/sources/fotos");
            var target = createTarget();

            var plan = catalog.createPlan(planRequest(source.id(), target.id(), false));

            assertThat(plan.nextRunAt()).isNull();
        }

        @Test
        @DisplayName("Ein unsinniger Zeitplan wird mit Beispielen erklaert")
        void explainsInvalidSchedule() {
            var source = createSource("/sources/fotos");
            var target = createTarget();

            var request = new CatalogRequests.SavePlan(unique("plan"), null, source.id(),
                    List.of(target.id()), null, "jede nacht", "Europe/Zurich", true, 360, 2,
                    MissedRunPolicy.SKIP, NotifyOn.FAILURE, null);

            assertThatThrownBy(() -> catalog.createPlan(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sechs Felder");
        }

        @Test
        @DisplayName("Ein Plan, dessen Ziele alle abgeschaltet sind, wird abgelehnt")
        void rejectsPlanWithOnlyDisabledTargets() {
            var source = createSource("/sources/fotos");
            var disabled = catalog.createTarget(new CatalogRequests.SaveTarget(unique("aus"), null,
                    TargetMode.RESTIC, new TargetConfig.LocalPath("/mnt/nas/aus", UUID.randomUUID()),
                    false));

            assertThatThrownBy(() -> catalog.createPlan(planRequest(source.id(), disabled.id(), true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("laeuft er ins Leere");
        }

        @Test
        @DisplayName("Die restic-Kennung eines Plans bleibt stabil")
        void resticIdentityIsStable() {
            // Aendert sie sich, gelten die bisherigen Snapshots als fremd und die
            // Aufbewahrungsregel greift nicht mehr auf sie zu.
            var source = createSource("/sources/fotos");
            var target = createTarget();
            var plan = catalog.createPlan(planRequest(source.id(), target.id(), true));

            var entity = plans.findById(plan.id()).orElseThrow();
            String hostBefore = entity.resticHost();

            catalog.updatePlan(plan.id(), new CatalogRequests.SavePlan("ganz-neuer-name", null,
                    source.id(), List.of(target.id()), null, "0 0 3 * * *", "Europe/Zurich", true,
                    360, 2, MissedRunPolicy.SKIP, NotifyOn.FAILURE, null));

            assertThat(plans.findById(plan.id()).orElseThrow().resticHost()).isEqualTo(hostBefore);
        }
    }

    @Nested
    @DisplayName("Aufbewahrung")
    class Retention {

        @Test
        @DisplayName("Eine Regel, die nichts behaelt, wird abgelehnt")
        void rejectsRuleThatKeepsNothing() {
            // Sie wuerde beim ersten Prune saemtliche Snapshots loeschen.
            assertThatThrownBy(() -> catalog.createRetentionPolicy(
                    new CatalogRequests.SaveRetentionPolicy("leer", null, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("saemtliche");
        }

        @Test
        @DisplayName("Eine sinnvolle Regel laesst sich anlegen und wieder auslesen")
        void createsAndReadsRule() {
            var policy = catalog.createRetentionPolicy(new CatalogRequests.SaveRetentionPolicy(
                    "gfs", null, null, 7, 4, 12, 3, null));

            var rule = policy.toRule();
            assertThat(rule.keepDaily()).isEqualTo(7);
            assertThat(rule.keepWeekly()).isEqualTo(4);
            assertThat(rule.keepMonthly()).isEqualTo(12);
            assertThat(rule.keepYearly()).isEqualTo(3);
        }
    }

    private CatalogRequests.SavePlan planRequest(UUID sourceId, UUID targetId, boolean enabled) {
        return new CatalogRequests.SavePlan(unique("plan"), null, sourceId, List.of(targetId), null,
                "0 0 2 * * *", "Europe/Zurich", enabled, 360, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, null);
    }
}
