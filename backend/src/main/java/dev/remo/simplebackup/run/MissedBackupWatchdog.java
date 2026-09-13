package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews.PlanView;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.notification.Notification;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Totmannschalter: schlaegt Alarm, wenn eine Sicherung ausbleibt.
 *
 * <p>Das ist der gefaehrlichste Zustand ueberhaupt, gefaehrlicher als ein Fehlschlag. Ein
 * fehlgeschlagener Lauf meldet sich; ein Lauf, der gar nicht erst startet, schweigt. Und
 * Schweigen wird fuer ein gutes Zeichen gehalten -- bis man die Daten braucht.
 *
 * <p>Gemessen wird am letzten <em>erfolgreichen</em> Lauf, nicht am letzten Start: Ein Plan,
 * der stuendlich startet und stuendlich scheitert, hat trotzdem seit Tagen nichts gesichert.
 */
@Component
class MissedBackupWatchdog {

    private static final Logger log = LoggerFactory.getLogger(MissedBackupWatchdog.class);

    static final String EVENT_TYPE = "BACKUP_OVERDUE";

    /** Zaehlt als erfolgreiche Sicherung -- beim Teilerfolg hat mindestens ein Ziel die Daten. */
    private static final List<RunStatus> SUCCESSFUL = List.of(RunStatus.SUCCESS, RunStatus.PARTIAL);

    private final CatalogService catalog;
    private final RunRepository runs;
    private final NotificationService notifications;
    private final RunProperties properties;
    private final Clock clock;

    MissedBackupWatchdog(CatalogService catalog, RunRepository runs, NotificationService notifications,
            RunProperties properties, Clock clock) {
        this.catalog = catalog;
        this.runs = runs;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${simplebackup.run.watchdog-interval:PT15M}")
    void checkForMissedBackups() {
        try {
            catalog.listPlans().forEach(this::check);
        } catch (RuntimeException e) {
            // Ein Fehler darf den Waechter nicht dauerhaft anhalten -- sonst faellt genau
            // das aus, was den Ausfall melden soll.
            log.error("Pruefung auf ausgebliebene Sicherungen fehlgeschlagen", e);
        }
    }

    private void check(PlanView plan) {
        if (!plan.enabled() || plan.expectedIntervalMinutes() == null
                || plan.notifyOn() == NotifyOn.NEVER) {
            return;
        }

        Instant lastSuccess = lastSuccessfulRun(plan);
        Duration expected = Duration.ofMinutes(plan.expectedIntervalMinutes());
        Instant deadline = lastSuccess.plus(expected).plus(properties.watchdogGrace());

        if (clock.instant().isBefore(deadline)) {
            return;
        }
        // Einmal je Vorfall: Der Vorfall beginnt mit der letzten erfolgreichen Sicherung und
        // endet mit der naechsten. Bis dahin wird nicht erneut gemeldet.
        if (notifications.alreadyPublished(EVENT_TYPE, plan.id(), lastSuccess)) {
            return;
        }

        Duration elapsed = Duration.between(lastSuccess, clock.instant());
        log.warn("Plan {} hat seit {} nicht erfolgreich gesichert", plan.name(), lastSuccess);

        notifications.publish(new Notification(EVENT_TYPE, Severity.CRITICAL,
                "Sicherung ausgeblieben: " + plan.name(),
                bodyFor(plan, lastSuccess, expected, elapsed),
                plan.id(), null,
                Map.of("planName", plan.name(),
                        "lastSuccessfulRun", lastSuccess.toString(),
                        "expectedIntervalMinutes", plan.expectedIntervalMinutes(),
                        "overdueMinutes", Duration.between(deadline, clock.instant()).toMinutes())));
    }

    /**
     * Wann zuletzt tatsaechlich gesichert wurde.
     *
     * <p>Gab es nie einen erfolgreichen Lauf, zaehlt die Anlage des Plans: Ein Plan, der seit
     * seiner Einrichtung nie gelaufen ist, ist genau der Fall, den dieser Waechter finden soll.
     */
    private Instant lastSuccessfulRun(PlanView plan) {
        return runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(plan.id(), SUCCESSFUL)
                .map(BackupRun::getFinishedAt)
                .filter(java.util.Objects::nonNull)
                .orElse(plan.createdAt());
    }

    private static String bodyFor(PlanView plan, Instant lastSuccess, Duration expected,
            Duration elapsed) {

        String text = "Erwartet wird alle %s eine Sicherung, die letzte erfolgreiche liegt %s zurück (%s)."
                .formatted(humanize(expected), humanize(elapsed), lastSuccess);

        return plan.lastRunAt() == null
                ? text + "\n\nDieser Plan ist noch nie gelaufen."
                : text;
    }

    private static String humanize(Duration duration) {
        long hours = duration.toHours();
        if (hours < 1) {
            return amount(duration.toMinutes(), "Minute", "Minuten");
        }
        return hours < 48
                ? amount(hours, "Stunde", "Stunden")
                : amount(duration.toDays(), "Tag", "Tage");
    }

    /** Einzahl und Mehrzahl getrennt -- "1 Minuten" liest sich wie ein Fehler im Werkzeug. */
    private static String amount(long value, String one, String many) {
        return value + " " + (value == 1 ? one : many);
    }
}
