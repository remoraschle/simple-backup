package dev.remo.simplebackup.catalog;

import dev.remo.simplebackup.catalog.CatalogViews.Capacity;
import dev.remo.simplebackup.catalog.CatalogViews.CheckResult;
import dev.remo.simplebackup.catalog.CatalogViews.PlanView;
import dev.remo.simplebackup.catalog.CatalogViews.SourceView;
import dev.remo.simplebackup.catalog.CatalogViews.TargetReference;
import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.PathTranslationException;
import dev.remo.simplebackup.shared.RetentionRule;
import dev.remo.simplebackup.shared.ConflictException;
import dev.remo.simplebackup.shared.NotFoundException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Verwaltung von Quellen, Zielen, Plaenen und Aufbewahrungsregeln.
 *
 * <p>Die Pruefungen finden hier statt und nicht erst beim Lauf. Ein Plan, dessen Quelle ein
 * Runner gar nicht erreichen kann, muss beim Anlegen scheitern -- nicht nachts um drei.
 */
@Service
@Transactional
public class CatalogService {

    private final SourceRepository sources;
    private final TargetRepository targets;
    private final PlanRepository plans;
    private final RetentionPolicyRepository policies;
    private final MountTranslator mountTranslator;
    private final NextRunCalculator nextRunCalculator;
    private final ObjectMapper objectMapper;

    CatalogService(SourceRepository sources, TargetRepository targets, PlanRepository plans,
            RetentionPolicyRepository policies, MountTranslator mountTranslator,
            NextRunCalculator nextRunCalculator, ObjectMapper objectMapper) {
        this.sources = sources;
        this.targets = targets;
        this.plans = plans;
        this.policies = policies;
        this.mountTranslator = mountTranslator;
        this.nextRunCalculator = nextRunCalculator;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- Quellen

    @Transactional(readOnly = true)
    public List<SourceView> listSources() {
        return sources.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public SourceView getSource(UUID id) {
        return toView(requireSource(id));
    }

    public SourceView createSource(CatalogRequests.SaveSource request) {
        if (sources.existsByName(request.name())) {
            throw new ConflictException("Eine Quelle mit dem Namen '%s' existiert bereits"
                    .formatted(request.name()));
        }
        validateSourceConfig(request.config());

        var source = new BackupSource(request.name(), request.config().type(), request.description(),
                objectMapper.writeValueAsString(request.config()));
        return toView(sources.save(source));
    }

    public SourceView updateSource(UUID id, CatalogRequests.SaveSource request) {
        BackupSource source = requireSource(id);

        if (source.getType() != request.config().type()) {
            // Ein Typwechsel wuerde bestehende Snapshots zu einer Quelle zaehlen, die es so
            // nie gab. Loeschen und neu anlegen ist der ehrlichere Weg.
            throw new ConflictException("Der Typ einer Quelle laesst sich nicht nachtraeglich aendern");
        }
        validateSourceConfig(request.config());

        source.update(request.name(), request.description(),
                objectMapper.writeValueAsString(request.config()));
        return toView(sources.save(source));
    }

    public void deleteSource(UUID id) {
        BackupSource source = requireSource(id);
        if (plans.existsBySourceId(id)) {
            throw new ConflictException(
                    "Die Quelle '%s' wird von mindestens einem Plan verwendet".formatted(source.getName()));
        }
        sources.delete(source);
    }

    /**
     * Prueft, ob ein Runner die Quelle ueberhaupt erreichen koennte.
     *
     * <p>Das ist die wichtigste Pruefung beim Anlegen: Ein Pfad, der im Backend-Container
     * nicht eingehaengt ist, laesst sich keinem Host-Pfad zuordnen -- ein Runner saehe dort
     * nichts. Ohne diese Pruefung entstuende ein Plan, der jede Nacht ein leeres Verzeichnis
     * sichert.
     */
    private void validateSourceConfig(SourceConfig config) {
        if (!config.type().isImplemented()) {
            throw new ConflictException(
                    "Quellen vom Typ %s sind noch nicht umgesetzt".formatted(config.type()));
        }
        if (config instanceof SourceConfig.LocalPath localPath) {
            for (String path : localPath.paths()) {
                try {
                    mountTranslator.translate(path, true);
                } catch (PathTranslationException e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Ziele

    @Transactional(readOnly = true)
    public List<TargetView> listTargets() {
        return targets.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public TargetView getTarget(UUID id) {
        return toView(requireTarget(id));
    }

    public TargetView createTarget(CatalogRequests.SaveTarget request) {
        if (targets.existsByName(request.name())) {
            throw new ConflictException("Ein Ziel mit dem Namen '%s' existiert bereits"
                    .formatted(request.name()));
        }
        validateTargetConfig(request.config(), request.mode());

        var target = new BackupTarget(request.name(), request.config().type(), request.mode(),
                request.description(), objectMapper.writeValueAsString(request.config()),
                request.enabled());
        return toView(targets.save(target));
    }

    public TargetView updateTarget(UUID id, CatalogRequests.SaveTarget request) {
        BackupTarget target = requireTarget(id);

        if (target.getType() != request.config().type() || target.getMode() != request.mode()) {
            throw new ConflictException("""
                    Typ und Modus eines Ziels lassen sich nicht nachtraeglich aendern. Ein \
                    bestehendes restic-Repository waere danach nicht mehr lesbar.""");
        }
        validateTargetConfig(request.config(), request.mode());

        target.update(request.name(), request.description(),
                objectMapper.writeValueAsString(request.config()), request.enabled());
        return toView(targets.save(target));
    }

    public void deleteTarget(UUID id) {
        BackupTarget target = requireTarget(id);
        if (plans.existsByTargetId(id)) {
            throw new ConflictException(
                    "Das Ziel '%s' wird von mindestens einem Plan verwendet".formatted(target.getName()));
        }
        targets.delete(target);
    }

    private void validateTargetConfig(TargetConfig config, TargetMode mode) {
        if (!config.type().isImplemented()) {
            throw new ConflictException(
                    "Ziele vom Typ %s sind noch nicht umgesetzt".formatted(config.type()));
        }
        if (mode == TargetMode.RESTIC && config.repositoryPasswordCredentialId() == null) {
            throw new IllegalArgumentException("""
                    Ein restic-Ziel braucht ein Repository-Passwort. Ohne dieses Passwort \
                    sind die Sicherungen spaeter nicht mehr lesbar -- es gibt keine Hintertuer.""");
        }
        if (config instanceof TargetConfig.LocalPath localPath) {
            try {
                mountTranslator.translate(localPath.path(), false);
            } catch (PathTranslationException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ Plaene

    /**
     * Namen zu Plan-Kennungen.
     *
     * <p>Die Lauf-Liste soll zeigen, zu welchem Plan ein Lauf gehoert. Ein Lauf kennt aber
     * nur die Kennung -- eine Seite Laeufe schlaegt die Namen deshalb in einer Abfrage nach,
     * statt fuer jede Zeile einzeln.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> planNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return plans.findByIdIn(ids).stream()
                .collect(Collectors.toMap(PlanRepository.PlanName::getId, PlanRepository.PlanName::getName));
    }

    /**
     * Name und Benachrichtigungsregel eines Plans.
     *
     * <p>Fuer Meldungen, die von einem Plan handeln, aber keinen ganzen ausfuehrbaren Plan
     * brauchen -- etwa wenn schon das Laden des Plans gescheitert ist.
     */
    @Transactional(readOnly = true)
    public Optional<PlanNotification> planNotification(UUID planId) {
        return plans.findById(planId)
                .map(plan -> new PlanNotification(plan.getName(), plan.getNotifyOn()));
    }

    /** Was gebraucht wird, um ueber einen Plan zu benachrichtigen. */
    public record PlanNotification(String name, NotifyOn notifyOn) {
    }

    @Transactional(readOnly = true)
    public List<PlanView> listPlans() {
        return plans.findAllByOrderByNameAsc().stream().map(CatalogService::toView).toList();
    }

    @Transactional(readOnly = true)
    public PlanView getPlan(UUID id) {
        return toView(requirePlan(id));
    }

    public PlanView createPlan(CatalogRequests.SavePlan request) {
        if (plans.existsByName(request.name())) {
            throw new ConflictException("Ein Plan mit dem Namen '%s' existiert bereits"
                    .formatted(request.name()));
        }
        nextRunCalculator.validate(request.cronExpression(), request.timezone());

        BackupSource source = requireSource(request.sourceId());
        List<BackupTarget> planTargets = resolveTargets(request.targetIds());

        var plan = new BackupPlan(request.name(), source, planTargets, request.cronExpression(),
                request.timezone());
        applyRequest(plan, request, planTargets);

        if (plan.isEnabled()) {
            nextRunCalculator.nextAfter(request.cronExpression(), request.timezone(), Instant.now())
                    .ifPresent(plan::scheduleNext);
        }
        return toView(plans.save(plan));
    }

    public PlanView updatePlan(UUID id, CatalogRequests.SavePlan request) {
        BackupPlan plan = requirePlan(id);
        nextRunCalculator.validate(request.cronExpression(), request.timezone());

        List<BackupTarget> planTargets = resolveTargets(request.targetIds());
        applyRequest(plan, request, planTargets);

        if (plan.isEnabled()) {
            nextRunCalculator.nextAfter(request.cronExpression(), request.timezone(), Instant.now())
                    .ifPresent(plan::scheduleNext);
        } else {
            // Ein abgeschalteter Plan hat keinen naechsten Termin; sonst liefe er nach dem
            // Wiedereinschalten sofort los.
            plan.scheduleNext(null);
        }
        return toView(plans.save(plan));
    }

    public void deletePlan(UUID id) {
        plans.delete(requirePlan(id));
    }

    private void applyRequest(BackupPlan plan, CatalogRequests.SavePlan request,
            List<BackupTarget> planTargets) {

        RetentionPolicy policy = request.retentionPolicyId() == null ? null
                : policies.findById(request.retentionPolicyId())
                        .orElseThrow(() -> new NotFoundException("Aufbewahrungsregel nicht gefunden"));

        plan.update(request.name(), request.description(), planTargets, request.cronExpression(),
                request.timezone(), request.enabled(), request.timeoutMinutes(), request.maxRetries(),
                request.missedRunPolicy(), request.notifyOn(), request.expectedIntervalMinutes(), policy);
    }

    private List<BackupTarget> resolveTargets(List<UUID> targetIds) {
        List<BackupTarget> resolved = targetIds.stream().map(this::requireTarget).toList();

        if (resolved.stream().noneMatch(BackupTarget::isEnabled)) {
            throw new IllegalArgumentException(
                    "Mindestens ein Ziel des Plans muss aktiv sein, sonst laeuft er ins Leere");
        }
        return resolved;
    }

    // --------------------------------------------------------- Aufbewahrung

    @Transactional(readOnly = true)
    public List<RetentionPolicy> listRetentionPolicies() {
        return policies.findAll();
    }

    public RetentionPolicy createRetentionPolicy(CatalogRequests.SaveRetentionPolicy request) {
        if (policies.existsByName(request.name())) {
            throw new ConflictException("Eine Regel mit dem Namen '%s' existiert bereits"
                    .formatted(request.name()));
        }
        // Der Konstruktor lehnt eine Regel ab, die nichts behaelt.
        var rule = new RetentionRule(request.keepLast(), request.keepHourly(), request.keepDaily(),
                request.keepWeekly(), request.keepMonthly(), request.keepYearly(),
                request.keepWithinDays());

        return policies.save(new RetentionPolicy(request.name(), rule));
    }

    public void deleteRetentionPolicy(UUID id) {
        RetentionPolicy policy = policies.findById(id)
                .orElseThrow(() -> new NotFoundException("Aufbewahrungsregel nicht gefunden"));

        if (plans.existsByRetentionPolicyId(id)) {
            throw new ConflictException(
                    "Die Regel '%s' wird von mindestens einem Plan verwendet".formatted(policy.getName()));
        }
        policies.delete(policy);
    }

    // ------------------------------------------------------ Fuer die Ausfuehrung

    /**
     * Wandelt einen Plan in ein Wertobjekt um, mit dem die Ausfuehrung arbeiten kann.
     *
     * <p>Ohne Entitaeten und ohne offene Transaktion: Ein Backup laeuft Stunden.
     */
    @Transactional(readOnly = true)
    public ExecutablePlan toExecutable(UUID planId) {
        BackupPlan plan = requirePlan(planId);

        List<ExecutableTarget> executableTargets = plan.getTargets().stream()
                .map(target -> new ExecutableTarget(target.getId(), target.getName(), target.getMode(),
                        objectMapper.readValue(target.getConfig(), TargetConfig.class), target.isEnabled()))
                .toList();

        return new ExecutablePlan(
                plan.getId(),
                plan.getName(),
                plan.resticHost(),
                plan.resticTag(),
                objectMapper.readValue(plan.getSource().getConfig(), SourceConfig.class),
                executableTargets,
                java.time.Duration.ofMinutes(plan.getTimeoutMinutes()),
                plan.getRetentionPolicy() == null ? null : plan.getRetentionPolicy().toRule(),
                plan.getNotifyOn());
    }

    /**
     * Uebernimmt faellige Plaene zur Ausfuehrung.
     *
     * <p>Die Abfrage sperrt die gefundenen Zeilen und ueberspringt bereits gesperrte. Mehrere
     * Instanzen koennen damit gleichzeitig suchen, ohne sich zu blockieren, und keine zwei
     * uebernehmen denselben Plan.
     *
     * <p>Der naechste Termin wird sofort gesetzt, noch innerhalb derselben Transaktion --
     * sonst faende der naechste Durchgang denselben Plan erneut.
     */
    public List<UUID> claimDuePlans(int limit) {
        Instant now = Instant.now();
        List<BackupPlan> due = plans.findDuePlansForUpdate(now, limit);

        for (BackupPlan plan : due) {
            nextRunCalculator.nextAfter(plan.getCronExpression(), plan.getTimezone(), now)
                    .ifPresentOrElse(plan::scheduleNext, () -> plan.scheduleNext(null));
            plans.save(plan);
        }
        return due.stream().map(BackupPlan::getId).toList();
    }

    /** Haelt das Ergebnis eines Laufs am Plan fest, fuer die Uebersicht. */
    public void recordRunResult(UUID planId, Instant at, String status) {
        BackupPlan plan = requirePlan(planId);
        plan.recordRun(at, status);
        plans.save(plan);
    }

    /**
     * Setzt den naechsten Termin nach einem Stillstand neu.
     *
     * <p>Wird beim Start aufgerufen: War der Server laenger aus, liegt der gespeicherte
     * Termin in der Vergangenheit. Je nach Einstellung wird ein Lauf nachgeholt oder auf den
     * naechsten regulaeren Termin gewartet.
     */
    public int rescheduleAfterDowntime() {
        Instant now = Instant.now();
        int adjusted = 0;

        for (BackupPlan plan : plans.findAll()) {
            if (!plan.isEnabled() || plan.getNextRunAt() == null || !plan.getNextRunAt().isBefore(now)) {
                continue;
            }
            var next = nextRunCalculator.resolveAfterDowntime(plan.getCronExpression(), plan.getTimezone(),
                    plan.getNextRunAt(), now, plan.getMissedRunPolicy());

            plan.scheduleNext(next.orElse(null));
            plans.save(plan);
            adjusted++;
        }
        return adjusted;
    }

    // ------------------------------------------------------------ Hilfsmittel

    BackupSource requireSource(UUID id) {
        return sources.findById(id)
                .orElseThrow(() -> new NotFoundException("Quelle %s nicht gefunden".formatted(id)));
    }

    BackupTarget requireTarget(UUID id) {
        return targets.findById(id)
                .orElseThrow(() -> new NotFoundException("Ziel %s nicht gefunden".formatted(id)));
    }

    BackupPlan requirePlan(UUID id) {
        return plans.findById(id)
                .orElseThrow(() -> new NotFoundException("Plan %s nicht gefunden".formatted(id)));
    }

    private SourceView toView(BackupSource source) {
        return new SourceView(source.getId(), source.getName(), source.getType(),
                source.getDescription(), objectMapper.readValue(source.getConfig(), SourceConfig.class),
                toCheckResult(source.getLastCheckAt(), source.getLastCheckOk(), source.getLastCheckMessage()),
                source.getCreatedAt(), source.getUpdatedAt());
    }

    private TargetView toView(BackupTarget target) {
        return new TargetView(target.getId(), target.getName(), target.getType(), target.getMode(),
                target.getDescription(), objectMapper.readValue(target.getConfig(), TargetConfig.class),
                target.isEnabled(),
                toCheckResult(target.getLastCheckAt(), target.getLastCheckOk(), target.getLastCheckMessage()),
                new Capacity(target.getCapacityBytes(), target.getFreeBytes(), target.getUsedBytes()),
                target.getCreatedAt(), target.getUpdatedAt());
    }

    private static CheckResult toCheckResult(Instant at, Boolean successful, String message) {
        return at == null ? null : new CheckResult(at, Boolean.TRUE.equals(successful), message);
    }

    private static PlanView toView(BackupPlan plan) {
        return new PlanView(plan.getId(), plan.getName(), plan.getDescription(),
                plan.getSource().getId(), plan.getSource().getName(),
                plan.getTargets().stream()
                        .map(target -> new TargetReference(target.getId(), target.getName(), target.getMode()))
                        .toList(),
                plan.getRetentionPolicy() == null ? null : plan.getRetentionPolicy().getId(),
                plan.getCronExpression(), plan.getTimezone(), plan.isEnabled(), plan.getTimeoutMinutes(),
                plan.getMaxRetries(), plan.getMissedRunPolicy(), plan.getNotifyOn(),
                plan.getExpectedIntervalMinutes(), plan.getNextRunAt(), plan.getLastRunAt(),
                plan.getLastRunStatus(), plan.getCreatedAt());
    }
}
