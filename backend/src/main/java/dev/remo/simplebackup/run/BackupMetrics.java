package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kennzahlen je Plan fuer Prometheus.
 *
 * <p>Die wichtigste davon ist {@code simplebackup_plan_last_success_age_seconds}. Sie
 * beantwortet die einzige Frage, die im Ernstfall zaehlt: Wie alt ist die neueste Sicherung,
 * die sich wirklich zurueckspielen liesse? Ein Zaehler fehlgeschlagener Laeufe beantwortet
 * sie nicht -- ein Plan, der gar nicht mehr startet, erzeugt keine Fehlschlaege.
 *
 * <p>Deshalb wird das Alter auch dann gemeldet, wenn ein Plan noch nie erfolgreich war; es
 * zaehlt dann ab seiner Anlage. Sonst waere ein Plan, der seit seiner Einrichtung nie
 * gelaufen ist, der einzige, ueber den die Ueberwachung schweigt.
 *
 * <p>Die Alarmregel dazu braucht keine Pflege, weil der erwartete Abstand als eigene
 * Kennzahl mitkommt:
 * {@snippet lang = "yaml":
 * expr: simplebackup_plan_expected_interval_seconds > 0
 *   and simplebackup_plan_last_success_age_seconds > simplebackup_plan_expected_interval_seconds * 2
 * }
 *
 * <p>Die Werte werden regelmaessig neu erhoben und nicht bei jedem Abruf: Ein Abruf soll die
 * Datenbank nicht belasten, und Prometheus fragt oefter, als sich hier etwas aendert.
 */
@Component
class BackupMetrics {

    private static final Logger log = LoggerFactory.getLogger(BackupMetrics.class);

    private final CatalogService catalog;
    private final RunRepository runs;
    private final MeterRegistry registry;
    private final Clock clock;

    private final MultiGauge lastSuccessAge;
    private final MultiGauge lastSuccessTime;
    private final MultiGauge lastDuration;
    private final MultiGauge lastBytesProcessed;
    private final MultiGauge lastBytesAdded;
    private final MultiGauge expectedInterval;
    private final MultiGauge planEnabled;
    private final MultiGauge targetFree;
    private final MultiGauge targetCapacity;

    BackupMetrics(CatalogService catalog, RunRepository runs, MeterRegistry registry, Clock clock) {
        this.catalog = catalog;
        this.runs = runs;
        this.registry = registry;
        this.clock = clock;

        this.lastSuccessAge = gauge("simplebackup.plan.last.success.age", "seconds",
                "Alter der letzten erfolgreichen Sicherung, ersatzweise seit Anlage des Plans");
        this.lastSuccessTime = gauge("simplebackup.plan.last.success.timestamp", "seconds",
                "Zeitpunkt der letzten erfolgreichen Sicherung als Unixzeit, 0 wenn es keine gibt");
        this.lastDuration = gauge("simplebackup.plan.last.duration", "seconds",
                "Dauer des letzten abgeschlossenen Laufs");
        this.lastBytesProcessed = gauge("simplebackup.plan.last.bytes.processed", "bytes",
                "Vom letzten Lauf gelesene Datenmenge");
        this.lastBytesAdded = gauge("simplebackup.plan.last.bytes.added", "bytes",
                "Vom letzten Lauf tatsaechlich geschriebene Datenmenge nach Deduplizierung");
        this.expectedInterval = gauge("simplebackup.plan.expected.interval", "seconds",
                "Erwarteter Abstand zwischen erfolgreichen Sicherungen, 0 wenn nichts erwartet wird");
        this.planEnabled = gauge("simplebackup.plan.enabled", null,
                "1 fuer einen aktiven Plan, 0 fuer einen abgeschalteten");
        this.targetFree = gauge("simplebackup.target.free", "bytes",
                "Freier Platz auf dem Ziel bei der letzten Pruefung");
        this.targetCapacity = gauge("simplebackup.target.capacity", "bytes",
                "Gesamtgroesse des Ziels bei der letzten Pruefung");

        Gauge.builder("simplebackup.runs.active", runs,
                        repository -> repository.countByStatusIn(
                                List.of(RunStatus.QUEUED, RunStatus.RUNNING)))
                .description("Laufende und wartende Laeufe")
                .register(registry);
    }

    private MultiGauge gauge(String name, String unit, String description) {
        return MultiGauge.builder(name).baseUnit(unit).description(description).register(registry);
    }

    /**
     * Zaehlt einen abgeschlossenen Lauf.
     *
     * <p>Getrennt nach Zustand, damit sich {@code PARTIAL} von {@code SUCCESS} unterscheiden
     * laesst -- ein Teilerfolg sieht in einer Uebersicht gruen aus und ist es nicht.
     */
    void runFinished(UUID planId, RunStatus status) {
        registry.counter("simplebackup.runs", tagsOf(planId, planName(planId))
                .and("status", status.name())).increment();
    }

    @Scheduled(fixedDelayString = "${simplebackup.run.metrics-interval:PT1M}")
    @Transactional(readOnly = true)
    void refresh() {
        try {
            refreshPlans();
            refreshTargets();
        } catch (RuntimeException e) {
            // Eine Kennzahl, die nicht erhoben werden konnte, darf den Betrieb nicht stoeren.
            // Prometheus sieht dann einen veralteten Wert -- und die Meldung steht im Log.
            log.warn("Kennzahlen liessen sich nicht erheben", e);
        }
    }

    private void refreshPlans() {
        Instant now = clock.instant();
        var ages = new ArrayList<MultiGauge.Row<?>>();
        var successTimes = new ArrayList<MultiGauge.Row<?>>();
        var durations = new ArrayList<MultiGauge.Row<?>>();
        var processed = new ArrayList<MultiGauge.Row<?>>();
        var added = new ArrayList<MultiGauge.Row<?>>();
        var intervals = new ArrayList<MultiGauge.Row<?>>();
        var enabled = new ArrayList<MultiGauge.Row<?>>();

        for (CatalogViews.PlanView plan : catalog.listPlans()) {
            Tags tags = tagsOf(plan.id(), plan.name());

            Instant lastSuccess = runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(
                            plan.id(), List.of(RunStatus.SUCCESS, RunStatus.PARTIAL))
                    .map(BackupRun::getFinishedAt)
                    .orElse(null);

            // Ohne Erfolg zaehlt das Alter ab der Anlage des Plans -- siehe Klassenkommentar.
            Instant since = lastSuccess != null ? lastSuccess : plan.createdAt();
            ages.add(MultiGauge.Row.of(tags, Duration.between(since, now).toSeconds()));
            successTimes.add(MultiGauge.Row.of(tags,
                    lastSuccess == null ? 0L : lastSuccess.getEpochSecond()));

            runs.findFirstByPlanIdAndFinishedAtNotNullOrderByFinishedAtDesc(plan.id())
                    .ifPresent(run -> {
                        durations.add(MultiGauge.Row.of(tags, run.getDuration().toSeconds()));
                        processed.add(MultiGauge.Row.of(tags, orZero(run.getBytesProcessed())));
                        added.add(MultiGauge.Row.of(tags, orZero(run.getBytesTransferred())));
                    });

            intervals.add(MultiGauge.Row.of(tags, plan.expectedIntervalMinutes() == null
                    ? 0L : plan.expectedIntervalMinutes() * 60L));
            enabled.add(MultiGauge.Row.of(tags, plan.enabled() ? 1 : 0));
        }

        // Mit Ueberschreiben: Ein geloeschter Plan soll verschwinden und nicht als ewig
        // alternde Sicherung stehen bleiben und Alarm ausloesen.
        lastSuccessAge.register(ages, true);
        lastSuccessTime.register(successTimes, true);
        lastDuration.register(durations, true);
        lastBytesProcessed.register(processed, true);
        lastBytesAdded.register(added, true);
        expectedInterval.register(intervals, true);
        planEnabled.register(enabled, true);
    }

    private void refreshTargets() {
        var free = new ArrayList<MultiGauge.Row<?>>();
        var capacity = new ArrayList<MultiGauge.Row<?>>();

        for (CatalogViews.TargetView target : catalog.listTargets()) {
            var space = target.capacity();
            if (space == null || space.totalBytes() == null) {
                continue;
            }
            Tags tags = Tags.of("target", target.name(), "targetId", target.id().toString());
            free.add(MultiGauge.Row.of(tags, orZero(space.freeBytes())));
            capacity.add(MultiGauge.Row.of(tags, space.totalBytes()));
        }

        targetFree.register(free, true);
        targetCapacity.register(capacity, true);
    }

    /**
     * Name und Kennung gemeinsam: Der Name ist lesbar, kann sich aber aendern; die Kennung
     * bleibt und macht eine Zeitreihe ueber eine Umbenennung hinweg wiederfindbar.
     */
    private static Tags tagsOf(UUID planId, String planName) {
        return Tags.of("plan", planName, "planId", planId.toString());
    }

    private String planName(UUID planId) {
        return catalog.planNames(List.of(planId)).getOrDefault(planId, planId.toString());
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
