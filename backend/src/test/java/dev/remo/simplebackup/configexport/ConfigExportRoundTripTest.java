package dev.remo.simplebackup.configexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.IntegrationTestBase;
import dev.remo.simplebackup.catalog.CatalogRequests;
import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews;
import dev.remo.simplebackup.catalog.MissedRunPolicy;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.catalog.TargetMode;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.notification.ChannelConfig;
import dev.remo.simplebackup.notification.NotificationRequests;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.NotificationViews;
import dev.remo.simplebackup.notification.Severity;
import dev.remo.simplebackup.secret.CredentialSummary;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.secret.CredentialType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Der Ernstfall, fuer den das Archiv existiert: Die Anwendung ist weg, und alles muss aus
 * einer Datei zurueckkommen.
 *
 * <p>Der Test spielt genau das durch -- ausgeben, loeschen, einspielen -- und prueft dabei
 * das, was leicht schiefgeht und spaet auffaellt: dass die Verweise <b>mitgezogen</b>
 * werden. Nach dem Einspielen haben alle Eintraege neue Kennungen. Zeigt das Ziel danach auf
 * das alte Repository-Passwort, laesst sich die Anwendung fehlerfrei bedienen und scheitert
 * erst beim naechsten naechtlichen Lauf.
 */
@Import(ConfigExportRoundTripTest.TestMounts.class)
class ConfigExportRoundTripTest extends IntegrationTestBase {

    /** Im Test laeuft die Anwendung nicht in einem Container und haette sonst keine Mounts. */
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

    private static final char[] PASSWORD = "archivpasswort-fuer-den-test".toCharArray();
    private static final String REPOSITORY_PASSWORD = "geheimes-repository-passwort";

    @Autowired
    private ConfigExportService service;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private CredentialService credentials;

    @Autowired
    private NotificationService notifications;

    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "export-" + UUID.randomUUID();
        createEverything();
    }

    @AfterEach
    void tearDown() {
        // In beliebiger Reihenfolge unmoeglich: Ein Plan haelt Quelle und Ziel fest.
        plan().ifPresent(plan -> catalog.deletePlan(plan.id()));
        target().ifPresent(target -> catalog.deleteTarget(target.id()));
        source().ifPresent(source -> catalog.deleteSource(source.id()));
        channel().ifPresent(channel -> notifications.deleteChannel(channel.id()));
        catalog.listRetentionPolicies().stream()
                .filter(policy -> policy.getName().startsWith(prefix))
                .forEach(policy -> catalog.deleteRetentionPolicy(policy.getId()));
        credentials.findAll().stream()
                .filter(credential -> credential.name().startsWith(prefix))
                .forEach(credential -> credentials.delete(credential.id()));
    }

    private void createEverything() {
        UUID repositoryPassword = credentials.create(name("repo"),
                CredentialType.RESTIC_REPOSITORY_PASSWORD, "Passwort des Repositorys",
                REPOSITORY_PASSWORD).id();

        UUID webhookToken = credentials.create(name("token"), CredentialType.API_TOKEN, null,
                "Bearer geheimes-webhook-token").id();

        UUID policy = catalog.createRetentionPolicy(new CatalogRequests.SaveRetentionPolicy(
                name("aufbewahrung"), 7, null, 14, 8, 12, null, null)).getId();

        UUID source = catalog.createSource(new CatalogRequests.SaveSource(name("quelle"),
                "Die Fotos", new SourceConfig.LocalPath(List.of("/sources/fotos"), List.of("*.tmp"),
                false))).id();

        UUID target = catalog.createTarget(new CatalogRequests.SaveTarget(name("ziel"), null,
                TargetMode.RESTIC, new TargetConfig.LocalPath("/mnt/nas/backups", repositoryPassword),
                true)).id();

        catalog.createPlan(new CatalogRequests.SavePlan(name("plan"), "Jede Nacht", source,
                List.of(target), policy, "0 0 2 * * *", "Europe/Zurich", true, 120, 2,
                MissedRunPolicy.SKIP, NotifyOn.FAILURE, 1440));

        notifications.createChannel(new NotificationRequests.SaveChannel(name("kanal"),
                new ChannelConfig.Webhook("https://example.invalid/haken", "Authorization",
                        webhookToken), true, Severity.WARNING));
    }

    /** Loescht alles, was der Test angelegt hat -- der simulierte Verlust. */
    private void deleteEverything() {
        tearDown();

        assertThat(plan()).isEmpty();
        assertThat(credentials.findAll().stream().map(CredentialSummary::name))
                .noneMatch(name -> name.startsWith(prefix));
    }

    @Test
    @DisplayName("Nach Verlust und Einspielen steht die Konfiguration wieder")
    void restoresEverything() {
        byte[] archive = service.export(PASSWORD);
        deleteEverything();

        var report = service.importArchive(archive, PASSWORD);

        assertThat(report.warnings()).isEmpty();
        assertThat(plan()).isPresent();
        assertThat(source()).isPresent();
        assertThat(target()).isPresent();
        assertThat(channel()).isPresent();
        assertThat(catalog.listRetentionPolicies())
                .anyMatch(policy -> policy.getName().equals(name("aufbewahrung")));
    }

    @Test
    @DisplayName("Das Repository-Passwort kommt im Klartext zurueck")
    void restoresSecrets() {
        // Der eigentliche Zweck: Ein restic-Repository ohne sein Passwort ist Rauschen.
        byte[] archive = service.export(PASSWORD);
        deleteEverything();

        service.importArchive(archive, PASSWORD);

        UUID restored = credential("repo").orElseThrow().id();
        assertThat(credentials.reveal(restored)).isEqualTo(REPOSITORY_PASSWORD);
    }

    @Test
    @DisplayName("Die Verweise zeigen nach dem Einspielen auf die neuen Eintraege")
    void rewritesReferences() {
        UUID oldCredential = credential("repo").orElseThrow().id();
        UUID oldSource = source().orElseThrow().id();
        UUID oldTarget = target().orElseThrow().id();

        byte[] archive = service.export(PASSWORD);
        deleteEverything();
        service.importArchive(archive, PASSWORD);

        UUID newCredential = credential("repo").orElseThrow().id();
        assertThat(newCredential).isNotEqualTo(oldCredential);

        // Das Ziel verweist auf den neu angelegten Zugang, nicht auf den verschwundenen.
        var restoredTarget = target().orElseThrow();
        assertThat(restoredTarget.config().repositoryPasswordCredentialId()).isEqualTo(newCredential);

        // Dasselbe fuer den Kanal: Auch dort steckt ein Zugang in der Konfiguration.
        var webhook = (ChannelConfig.Webhook) channel().orElseThrow().config();
        assertThat(webhook.credentialId()).isEqualTo(credential("token").orElseThrow().id());

        var restoredPlan = plan().orElseThrow();
        assertThat(restoredPlan.sourceId()).isEqualTo(source().orElseThrow().id())
                .isNotEqualTo(oldSource);
        assertThat(restoredPlan.targets()).singleElement()
                .satisfies(reference -> assertThat(reference.id())
                        .isEqualTo(restoredTarget.id()).isNotEqualTo(oldTarget));
        assertThat(restoredPlan.retentionPolicyId()).isNotNull();
        assertThat(restoredPlan.expectedIntervalMinutes()).isEqualTo(1440);
    }

    @Test
    @DisplayName("Ein Plan behaelt seine Kennung")
    void keepsPlanIdentity() {
        // Aus der Kennung entstehen Host und Tag der Snapshots. Mit einer neuen waeren dem
        // eingespielten Plan seine eigenen bisherigen Sicherungen fremd -- die Aufbewahrung
        // liesse sie fuer immer liegen, und im Snapshot-Browser tauchten sie nicht auf.
        UUID planId = plan().orElseThrow().id();

        byte[] archive = service.export(PASSWORD);
        deleteEverything();
        service.importArchive(archive, PASSWORD);

        assertThat(plan().orElseThrow().id()).isEqualTo(planId);
    }

    @Test
    @DisplayName("Ein zwischenzeitlich umbenannter Plan wird nicht ein zweites Mal angelegt")
    void doesNotDuplicateRenamedPlan() {
        // Der Name aus dem Archiv ist dann frei, die Kennung aber nicht. Ohne Pruefung
        // scheiterte das ganze Einspielen an dieser einen Kollision.
        byte[] archive = service.export(PASSWORD);
        renamePlanTo(name("plan-neu"));

        var report = service.importArchive(archive, PASSWORD);

        assertThat(report.imported()).doesNotContainKey("Plan");
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("Kennung"));
        assertThat(catalog.listPlans().stream().filter(view -> view.name().startsWith(prefix)))
                .hasSize(1);

        renamePlanTo(name("plan"));
    }

    private void renamePlanTo(String newName) {
        var view = catalog.listPlans().stream()
                .filter(plan -> plan.name().startsWith(prefix)).findFirst().orElseThrow();

        catalog.updatePlan(view.id(), new CatalogRequests.SavePlan(newName, view.description(),
                view.sourceId(), view.targets().stream().map(CatalogViews.TargetReference::id).toList(),
                view.retentionPolicyId(), view.cronExpression(), view.timezone(), view.enabled(),
                view.timeoutMinutes(), view.maxRetries(), view.missedRunPolicy(), view.notifyOn(),
                view.expectedIntervalMinutes()));
    }

    @Test
    @DisplayName("Ein zweites Einspielen legt nichts doppelt an")
    void secondImportChangesNothing() {
        // Der zweithaeufigste Ernstfall nach dem leeren System: derselbe Aufruf noch einmal,
        // weil man nicht sicher war, ob der erste durchlief.
        byte[] archive = service.export(PASSWORD);

        var report = service.importArchive(archive, PASSWORD);

        assertThat(report.imported()).isEmpty();
        assertThat(report.skipped()).containsKeys("Zugang", "Quelle", "Ziel", "Plan", "Kanal");
        assertThat(report.skipped().get("Plan")).contains(name("plan"));
        assertThat(catalog.listPlans().stream().filter(plan -> plan.name().equals(name("plan"))))
                .hasSize(1);
    }

    @Test
    @DisplayName("Mit falschem Passwort wird nichts eingespielt")
    void wrongPasswordChangesNothing() {
        byte[] archive = service.export(PASSWORD);
        deleteEverything();

        assertThatThrownBy(() -> service.importArchive(archive, "ganz-anderes-passwort".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(plan()).isEmpty();
        assertThat(credential("repo")).isEmpty();
    }

    @Test
    @DisplayName("Im Archiv steht kein Geheimnis im Klartext")
    void archiveLeaksNothing() {
        byte[] archive = service.export(PASSWORD);

        assertThat(new String(archive, StandardCharsets.ISO_8859_1))
                .doesNotContain(REPOSITORY_PASSWORD)
                .doesNotContain(name("plan"));
    }

    // ------------------------------------------------------------------ Helfer

    private String name(String suffix) {
        return prefix + "-" + suffix;
    }

    private Optional<CredentialSummary> credential(String suffix) {
        return credentials.findAll().stream()
                .filter(credential -> credential.name().equals(name(suffix))).findFirst();
    }

    private Optional<CatalogViews.SourceView> source() {
        return catalog.listSources().stream()
                .filter(view -> view.name().equals(name("quelle"))).findFirst();
    }

    private Optional<CatalogViews.TargetView> target() {
        return catalog.listTargets().stream()
                .filter(view -> view.name().equals(name("ziel"))).findFirst();
    }

    private Optional<CatalogViews.PlanView> plan() {
        return catalog.listPlans().stream()
                .filter(view -> view.name().equals(name("plan"))).findFirst();
    }

    private Optional<NotificationViews.ChannelView> channel() {
        return notifications.listChannels().stream()
                .filter(view -> view.name().equals(name("kanal"))).findFirst();
    }
}
