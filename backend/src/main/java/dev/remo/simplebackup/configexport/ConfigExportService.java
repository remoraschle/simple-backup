package dev.remo.simplebackup.configexport;

import dev.remo.simplebackup.catalog.CatalogRequests;
import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews;
import dev.remo.simplebackup.catalog.RetentionPolicy;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedChannel;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedCredential;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedPlan;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedRetentionPolicy;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedSource;
import dev.remo.simplebackup.configexport.ConfigArchiveContent.ExportedTarget;
import dev.remo.simplebackup.notification.ChannelConfig;
import dev.remo.simplebackup.notification.NotificationRequests;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.secret.CredentialService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gibt die gesamte Konfiguration als verschluesseltes Archiv aus und liest sie wieder ein.
 *
 * <p>Ausgegeben wird alles, was noetig ist, um die Anwendung anderswo neu aufzusetzen und
 * die vorhandenen Repositories weiter zu benutzen -- einschliesslich der Zugangsdaten im
 * Klartext. Ohne sie waere das Archiv wertlos, denn ein restic-Repository ohne sein Passwort
 * ist Rauschen.
 *
 * <p>Beim Einspielen wird nichts ueberschrieben. Ein Eintrag, dessen Name schon vergeben
 * ist, gilt als derselbe und wird uebersprungen; Verweise darauf zeigen dann auf den
 * vorhandenen Eintrag. Das macht das Einspielen wiederholbar und nimmt ihm die
 * Gefaehrlichkeit: Der haeufigste Ernstfall ist ein leeres System, der zweithaeufigste ein
 * versehentlicher zweiter Aufruf.
 */
@Service
@Transactional
public class ConfigExportService {

    private final CatalogService catalog;
    private final CredentialService credentials;
    private final NotificationService notifications;
    private final PasswordArchive archive;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ConfigExportService(CatalogService catalog, CredentialService credentials,
            NotificationService notifications, PasswordArchive archive, ObjectMapper objectMapper,
            Clock clock) {

        this.catalog = catalog;
        this.credentials = credentials;
        this.notifications = notifications;
        this.archive = archive;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param password Passwort des Archivs. Nicht das Anmeldepasswort und nicht der
     *                 Masterkey -- ein Archiv, das an einem der beiden haengt, hilft in
     *                 genau dem Fall nicht, fuer den es gedacht ist.
     */
    @Transactional(readOnly = true)
    public byte[] export(char[] password) {
        byte[] plaintext = objectMapper.writeValueAsBytes(collect());
        try {
            return archive.seal(plaintext, password);
        } finally {
            // Der Klartext enthaelt jedes Geheimnis dieser Anwendung. Er soll nicht laenger
            // als noetig auf dem Heap liegen und dort auf die naechste Speicherbereinigung
            // warten.
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private ConfigArchiveContent collect() {
        List<ExportedCredential> exportedCredentials = credentials.findAll().stream()
                .map(summary -> new ExportedCredential(summary.id(), summary.name(), summary.type(),
                        summary.description(), credentials.reveal(summary.id())))
                .toList();

        List<ExportedRetentionPolicy> exportedPolicies = catalog.listRetentionPolicies().stream()
                .map(policy -> new ExportedRetentionPolicy(policy.getId(), toRequest(policy)))
                .toList();

        List<ExportedSource> exportedSources = catalog.listSources().stream()
                .map(source -> new ExportedSource(source.id(), new CatalogRequests.SaveSource(
                        source.name(), source.description(), source.config())))
                .toList();

        List<ExportedTarget> exportedTargets = catalog.listTargets().stream()
                .map(target -> new ExportedTarget(target.id(), new CatalogRequests.SaveTarget(
                        target.name(), target.description(), target.mode(), target.config(),
                        target.enabled())))
                .toList();

        List<ExportedPlan> exportedPlans = catalog.listPlans().stream()
                .map(plan -> new ExportedPlan(plan.id(), toRequest(plan)))
                .toList();

        List<ExportedChannel> exportedChannels = notifications.listChannels().stream()
                .map(channel -> new ExportedChannel(channel.id(), new NotificationRequests.SaveChannel(
                        channel.name(), channel.config(), channel.enabled(), channel.minSeverity())))
                .toList();

        return new ConfigArchiveContent(ConfigArchiveContent.FORMAT_VERSION, clock.instant(),
                ConfigArchiveContent.APPLICATION, exportedCredentials, exportedPolicies,
                exportedSources, exportedTargets, exportedPlans, exportedChannels);
    }

    private static CatalogRequests.SaveRetentionPolicy toRequest(RetentionPolicy policy) {
        var rule = policy.toRule();
        return new CatalogRequests.SaveRetentionPolicy(policy.getName(), rule.keepLast(),
                rule.keepHourly(), rule.keepDaily(), rule.keepWeekly(), rule.keepMonthly(),
                rule.keepYearly(), rule.keepWithinDays());
    }

    private static CatalogRequests.SavePlan toRequest(CatalogViews.PlanView plan) {
        return new CatalogRequests.SavePlan(plan.name(), plan.description(), plan.sourceId(),
                plan.targets().stream().map(CatalogViews.TargetReference::id).toList(),
                plan.retentionPolicyId(), plan.cronExpression(), plan.timezone(), plan.enabled(),
                plan.timeoutMinutes(), plan.maxRetries(), plan.missedRunPolicy(), plan.notifyOn(),
                plan.expectedIntervalMinutes());
    }

    // ------------------------------------------------------------------ Einspielen

    public ImportReport importArchive(byte[] sealed, char[] password) {
        byte[] plaintext = archive.open(sealed, password);
        ConfigArchiveContent content;
        try {
            content = objectMapper.readValue(plaintext, ConfigArchiveContent.class);
        } catch (RuntimeException e) {
            // Entschluesselt hat es sich, ergibt aber keinen Inhalt. Die urspruengliche
            // Meldung nennt Feldnamen und Positionen -- fuer den Anwender wertlos, fuer
            // einen Angreifer ein Hinweis auf das Innenleben.
            throw new IllegalArgumentException("Das Archiv laesst sich nicht lesen", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        if (content.formatVersion() != ConfigArchiveContent.FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Archivinhalt der Version %d wird nicht unterstuetzt, erwartet wird %d"
                            .formatted(content.formatVersion(), ConfigArchiveContent.FORMAT_VERSION));
        }

        var report = new Report();
        Map<UUID, UUID> credentialIds = importCredentials(content, report);
        Map<UUID, UUID> policyIds = importPolicies(content, report);
        Map<UUID, UUID> sourceIds = importSources(content, credentialIds, report);
        Map<UUID, UUID> targetIds = importTargets(content, credentialIds, report);
        importPlans(content, sourceIds, targetIds, policyIds, report);
        importChannels(content, credentialIds, report);

        return report.toReport();
    }

    private Map<UUID, UUID> importCredentials(ConfigArchiveContent content, Report report) {
        Map<String, UUID> existing = index(credentials.findAll(), summary -> summary.name(),
                summary -> summary.id());
        Map<UUID, UUID> mapping = new HashMap<>();

        for (ExportedCredential exported : content.credentials()) {
            UUID present = existing.get(exported.name());
            if (present != null) {
                // Bewusst nicht ueberschrieben: Wer ein Archiv einspielt, will Fehlendes
                // ergaenzen. Ein Zugang, der hier schon liegt, ist wahrscheinlich der
                // neuere -- das Archiv ist per Definition ein Stand von frueher.
                mapping.put(exported.id(), present);
                report.skipped("Zugang", exported.name());
                continue;
            }
            var created = credentials.create(exported.name(), exported.type(),
                    exported.description(), exported.secret());
            mapping.put(exported.id(), created.id());
            report.imported("Zugang");
        }
        return mapping;
    }

    private Map<UUID, UUID> importPolicies(ConfigArchiveContent content, Report report) {
        Map<String, UUID> existing = index(catalog.listRetentionPolicies(), RetentionPolicy::getName,
                RetentionPolicy::getId);
        Map<UUID, UUID> mapping = new HashMap<>();

        for (ExportedRetentionPolicy exported : content.retentionPolicies()) {
            UUID present = existing.get(exported.definition().name());
            if (present != null) {
                mapping.put(exported.id(), present);
                report.skipped("Aufbewahrungsregel", exported.definition().name());
                continue;
            }
            mapping.put(exported.id(), catalog.createRetentionPolicy(exported.definition()).getId());
            report.imported("Aufbewahrungsregel");
        }
        return mapping;
    }

    private Map<UUID, UUID> importSources(ConfigArchiveContent content, Map<UUID, UUID> credentialIds,
            Report report) {

        Map<String, UUID> existing = index(catalog.listSources(), CatalogViews.SourceView::name,
                CatalogViews.SourceView::id);
        Map<UUID, UUID> mapping = new HashMap<>();

        for (ExportedSource exported : content.sources()) {
            var definition = exported.definition();
            UUID present = existing.get(definition.name());
            if (present != null) {
                mapping.put(exported.id(), present);
                report.skipped("Quelle", definition.name());
                continue;
            }
            var remapped = new CatalogRequests.SaveSource(definition.name(), definition.description(),
                    remapCredentials(definition.config(), credentialIds, SourceConfig.class));

            mapping.put(exported.id(), catalog.createSource(remapped).id());
            report.imported("Quelle");
        }
        return mapping;
    }

    private Map<UUID, UUID> importTargets(ConfigArchiveContent content, Map<UUID, UUID> credentialIds,
            Report report) {

        Map<String, UUID> existing = index(catalog.listTargets(), CatalogViews.TargetView::name,
                CatalogViews.TargetView::id);
        Map<UUID, UUID> mapping = new HashMap<>();

        for (ExportedTarget exported : content.targets()) {
            var definition = exported.definition();
            UUID present = existing.get(definition.name());
            if (present != null) {
                mapping.put(exported.id(), present);
                report.skipped("Ziel", definition.name());
                continue;
            }
            var remapped = new CatalogRequests.SaveTarget(definition.name(), definition.description(),
                    definition.mode(),
                    remapCredentials(definition.config(), credentialIds, TargetConfig.class),
                    definition.enabled());

            mapping.put(exported.id(), catalog.createTarget(remapped).id());
            report.imported("Ziel");
        }
        return mapping;
    }

    private void importPlans(ConfigArchiveContent content, Map<UUID, UUID> sourceIds,
            Map<UUID, UUID> targetIds, Map<UUID, UUID> policyIds, Report report) {

        List<CatalogViews.PlanView> present = catalog.listPlans();
        Set<String> existingNames = present.stream()
                .map(CatalogViews.PlanView::name).collect(java.util.stream.Collectors.toSet());
        Set<UUID> existingIds = present.stream()
                .map(CatalogViews.PlanView::id).collect(java.util.stream.Collectors.toSet());

        for (ExportedPlan exported : content.plans()) {
            var definition = exported.definition();
            if (existingNames.contains(definition.name())) {
                report.skipped("Plan", definition.name());
                continue;
            }
            if (existingIds.contains(exported.id())) {
                // Derselbe Plan unter anderem Namen: nach dem Ausgeben umbenannt. Ihn ein
                // zweites Mal anzulegen ginge nicht, und den vorhandenen zu ueberschreiben
                // waere das Gegenteil dessen, was dieses Einspielen sonst tut.
                report.warn("Plan '%s' uebersprungen: Seine Kennung gehoert hier bereits einem Plan"
                        .formatted(definition.name()));
                continue;
            }

            UUID sourceId = sourceIds.get(definition.sourceId());
            List<UUID> planTargets = definition.targetIds().stream()
                    .map(targetIds::get).filter(java.util.Objects::nonNull).toList();

            if (sourceId == null || planTargets.isEmpty()) {
                // Kann nur bei einem von Hand veraenderten Archiv auftreten. Dann lieber
                // diesen einen Plan auslassen und es sagen, als alles Uebrige zurueckrollen.
                report.warn("Plan '%s' uebersprungen: Quelle oder Ziel fehlt im Archiv"
                        .formatted(definition.name()));
                continue;
            }

            var remapped = new CatalogRequests.SavePlan(definition.name(), definition.description(),
                    sourceId, planTargets, policyIds.get(definition.retentionPolicyId()),
                    definition.cronExpression(), definition.timezone(), definition.enabled(),
                    definition.timeoutMinutes(), definition.maxRetries(), definition.missedRunPolicy(),
                    definition.notifyOn(), definition.expectedIntervalMinutes());

            // Unter der urspruenglichen Kennung: Aus ihr entstehen Host und Tag der
            // Snapshots. Mit einer neuen waeren dem Plan seine eigenen bisherigen
            // Sicherungen fremd -- genau die, um derentwillen das Archiv angelegt wurde.
            catalog.restorePlan(exported.id(), remapped);
            report.imported("Plan");
        }
    }

    private void importChannels(ConfigArchiveContent content, Map<UUID, UUID> credentialIds,
            Report report) {

        Set<String> existing = notifications.listChannels().stream()
                .map(dev.remo.simplebackup.notification.NotificationViews.ChannelView::name)
                .collect(java.util.stream.Collectors.toSet());

        for (ExportedChannel exported : content.channels()) {
            var definition = exported.definition();
            if (existing.contains(definition.name())) {
                report.skipped("Kanal", definition.name());
                continue;
            }
            notifications.createChannel(new NotificationRequests.SaveChannel(definition.name(),
                    remapCredentials(definition.config(), credentialIds, ChannelConfig.class),
                    definition.enabled(), definition.minSeverity()));
            report.imported("Kanal");
        }
    }

    /**
     * Ersetzt Verweise auf Zugangsdaten in einer beliebigen Konfiguration.
     *
     * <p>Bewusst ueber den JSON-Baum und nicht ueber die einzelnen Typen: Sonst muesste
     * jeder neue Quell-, Ziel- oder Kanaltyp hier nachgetragen werden, und wer das vergisst,
     * merkt es erst, wenn ein eingespielter Plan beim ersten Lauf an fehlenden Zugangsdaten
     * scheitert. Jede Kennung in einer Konfiguration ist ein Verweis auf einen Zugang --
     * etwas anderes steht dort nicht.
     */
    private <T> T remapCredentials(T config, Map<UUID, UUID> credentialIds, Class<T> type) {
        if (config == null || credentialIds.isEmpty()) {
            return config;
        }
        return objectMapper.treeToValue(rewrite(objectMapper.valueToTree(config), credentialIds), type);
    }

    private JsonNode rewrite(JsonNode node, Map<UUID, UUID> credentialIds) {
        if (node.isObject()) {
            var rewritten = objectMapper.createObjectNode();
            node.properties().forEach(property ->
                    rewritten.set(property.getKey(), rewrite(property.getValue(), credentialIds)));
            return rewritten;
        }
        if (node.isArray()) {
            var rewritten = objectMapper.createArrayNode();
            node.forEach(element -> rewritten.add(rewrite(element, credentialIds)));
            return rewritten;
        }
        if (node.isString()) {
            UUID replacement = credentialIds.get(toUuid(node.stringValue()));
            return replacement == null ? node : objectMapper.getNodeFactory()
                    .textNode(replacement.toString());
        }
        return node;
    }

    private static UUID toUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> Map<String, UUID> index(List<T> items, Function<T, String> name,
            Function<T, UUID> id) {

        Map<String, UUID> index = new HashMap<>();
        items.forEach(item -> index.put(name.apply(item), id.apply(item)));
        return index;
    }

    /**
     * Was das Einspielen bewirkt hat.
     *
     * @param imported je Art die Anzahl neu angelegter Eintraege
     * @param skipped  je Art die Namen der uebersprungenen Eintraege -- wer ein Archiv in
     *                 ein bestehendes System einspielt, will genau das sehen
     * @param warnings was nicht eingespielt werden konnte
     */
    public record ImportReport(Map<String, Integer> imported, Map<String, List<String>> skipped,
            List<String> warnings) {
    }

    /** Sammelt waehrend des Einspielens, was am Ende berichtet wird. */
    private static final class Report {

        private final Map<String, Integer> imported = new java.util.LinkedHashMap<>();
        private final Map<String, List<String>> skipped = new java.util.LinkedHashMap<>();
        private final Set<String> warnings = new LinkedHashSet<>();

        void imported(String kind) {
            imported.merge(kind, 1, Integer::sum);
        }

        void skipped(String kind, String name) {
            skipped.computeIfAbsent(kind, key -> new ArrayList<>()).add(name);
        }

        void warn(String message) {
            warnings.add(message);
        }

        ImportReport toReport() {
            return new ImportReport(Map.copyOf(imported), Map.copyOf(skipped), List.copyOf(warnings));
        }
    }
}
