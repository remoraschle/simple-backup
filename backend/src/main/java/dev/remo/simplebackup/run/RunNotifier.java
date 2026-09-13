package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.notification.Notification;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.Severity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Formuliert die Meldung zu einem beendeten Lauf.
 *
 * <p>Was benachrichtigt wird, entscheidet der Plan; wohin, entscheiden die Kanaele. Diese
 * Klasse verbindet beides und sagt, was passiert ist -- in einem Satz, der auch auf einem
 * Sperrbildschirm noch etwas aussagt.
 */
@Component
class RunNotifier {

    private static final Logger log = LoggerFactory.getLogger(RunNotifier.class);

    private final NotificationService notifications;
    private final CatalogService catalog;

    RunNotifier(NotificationService notifications, CatalogService catalog) {
        this.notifications = notifications;
        this.catalog = catalog;
    }

    /**
     * Meldet das Ergebnis eines Laufs, sofern der Plan das vorsieht.
     *
     * @param outcomes Ergebnis je Ziel; leer, wenn der Lauf gescheitert ist, bevor ein Ziel
     *                 an der Reihe war
     */
    void runFinished(UUID planId, UUID runId, RunStatus status, String errorSummary,
            List<BackupRunner.TargetOutcome> outcomes) {

        try {
            var plan = catalog.planNotification(planId).orElse(null);
            if (plan == null) {
                log.debug("Plan {} existiert nicht mehr, keine Meldung", planId);
                return;
            }
            if (!shouldNotify(plan.notifyOn(), status)) {
                return;
            }
            notifications.publish(new Notification(eventTypeFor(status), severityFor(status),
                    titleFor(status, plan.name()), bodyFor(status, plan.name(), errorSummary, outcomes),
                    planId, runId, payloadFor(plan.name(), status, outcomes)));

        } catch (RuntimeException e) {
            // Eine misslungene Meldung darf den Lauf nicht nachtraeglich kippen.
            log.error("Meldung zum Lauf {} liess sich nicht erzeugen", runId, e);
        }
    }

    private static boolean shouldNotify(NotifyOn notifyOn, RunStatus status) {
        return switch (notifyOn) {
            case NEVER -> false;
            case ALWAYS -> true;
            case FAILURE -> status != RunStatus.SUCCESS;
        };
    }

    /**
     * <p>Ein abgebrochener Lauf ist keine Katastrophe, sondern eine Entscheidung -- jemand
     * hat auf "Abbrechen" gedrueckt. Als kritisch gemeldet wuerde er nur abstumpfen.
     */
    private static Severity severityFor(RunStatus status) {
        return switch (status) {
            case SUCCESS -> Severity.INFO;
            case PARTIAL, CANCELLED -> Severity.WARNING;
            case FAILED, TIMEOUT -> Severity.CRITICAL;
            case QUEUED, RUNNING -> Severity.INFO;
        };
    }

    private static String eventTypeFor(RunStatus status) {
        return "RUN_" + status.name();
    }

    private static String titleFor(RunStatus status, String planName) {
        return switch (status) {
            case SUCCESS -> "Sicherung erfolgreich: " + planName;
            case PARTIAL -> "Sicherung nur teilweise: " + planName;
            case FAILED -> "Sicherung fehlgeschlagen: " + planName;
            case TIMEOUT -> "Sicherung im Zeitlimit abgebrochen: " + planName;
            case CANCELLED -> "Sicherung abgebrochen: " + planName;
            case QUEUED, RUNNING -> "Sicherung läuft: " + planName;
        };
    }

    /**
     * Der Text der Meldung.
     *
     * <p>Nennt jedes Ziel einzeln. Beim Teilerfolg ist genau das die Information, auf die es
     * ankommt: Welches Ziel hat die Daten, und welches nicht?
     */
    private static String bodyFor(RunStatus status, String planName, String errorSummary,
            List<BackupRunner.TargetOutcome> outcomes) {

        var text = new StringBuilder();

        if (outcomes.isEmpty()) {
            text.append("Der Lauf ist beendet worden, bevor ein Ziel an der Reihe war.");
        } else {
            for (var outcome : outcomes) {
                text.append(outcome.targetName()).append(": ");
                text.append(outcome.successful() ? "erfolgreich" : outcome.skipped() ? "übersprungen" : "fehlgeschlagen");
                if (outcome.message() != null && !outcome.successful()) {
                    text.append(" — ").append(outcome.message());
                }
                text.append('\n');
            }
        }
        if (errorSummary != null && !errorSummary.isBlank() && status != RunStatus.SUCCESS) {
            text.append('\n').append(errorSummary);
        }
        return text.toString().strip();
    }

    private static Map<String, Object> payloadFor(String planName, RunStatus status,
            List<BackupRunner.TargetOutcome> outcomes) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planName", planName);
        payload.put("status", status.name());
        payload.put("targets", outcomes.stream().map(outcome -> Map.of(
                "name", outcome.targetName(),
                "successful", outcome.successful(),
                "skipped", outcome.skipped())).toList());

        return payload;
    }
}
