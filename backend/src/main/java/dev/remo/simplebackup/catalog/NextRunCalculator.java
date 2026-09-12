package dev.remo.simplebackup.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Rechnet aus, wann ein Plan das naechste Mal faellig ist.
 *
 * <p>Die Berechnung geschieht in der Zeitzone des Plans, nicht in UTC. Ein Backup, das
 * "jede Nacht um 2 Uhr" laufen soll, muss auch nach der Zeitumstellung um 2 Uhr Ortszeit
 * laufen -- in UTC waere es im Sommer 0 Uhr und im Winter 1 Uhr.
 */
@Component
public class NextRunCalculator {

    /**
     * @param after Zeitpunkt, ab dem gesucht wird
     * @return der naechste Termin, oder leer bei einem Ausdruck, der nie wieder zutrifft
     */
    public Optional<Instant> nextAfter(String cronExpression, String timezone, Instant after) {
        CronExpression cron = parse(cronExpression);
        ZoneId zone = zoneOf(timezone);

        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, zone));
        return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
    }

    /**
     * Ermittelt den naechsten Termin, nachdem der Server eine Weile aus war.
     *
     * <p>Bei {@link MissedRunPolicy#CATCH_UP} ist der erste verpasste Termin faellig, sodass
     * genau ein Lauf nachgeholt wird. Bei {@link MissedRunPolicy#SKIP} wird auf den naechsten
     * regulaeren Termin gewartet -- sonst starteten nach einem laengeren Ausfall alle
     * verpassten Laeufe auf einmal und legten den Server lahm.
     *
     * @param lastScheduled letzter geplanter Termin, oder {@code null} bei einem neuen Plan
     */
    public Optional<Instant> resolveAfterDowntime(String cronExpression, String timezone,
            Instant lastScheduled, Instant now, MissedRunPolicy policy) {

        if (lastScheduled == null || !lastScheduled.isBefore(now)) {
            return nextAfter(cronExpression, timezone, now);
        }
        if (policy == MissedRunPolicy.CATCH_UP) {
            // Der verpasste Termin bleibt faellig: Genau ein Lauf wird nachgeholt.
            return Optional.of(lastScheduled);
        }
        return nextAfter(cronExpression, timezone, now);
    }

    /** Prueft einen Ausdruck, ohne ihn anzuwenden. Fuer die Eingabepruefung. */
    public void validate(String cronExpression, String timezone) {
        parse(cronExpression);
        zoneOf(timezone);
    }

    private static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Ein Zeitplan ist erforderlich");
        }
        try {
            return CronExpression.parse(expression.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("""
                    "%s" ist kein gueltiger Zeitplan.

                    Erwartet werden sechs Felder: Sekunde Minute Stunde Tag Monat Wochentag.
                    Beispiele: "0 0 2 * * *" jede Nacht um 2 Uhr, \
                    "0 30 3 * * SUN" sonntags um 3:30 Uhr."""
                    .formatted(expression), e);
        }
    }

    private static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("Eine Zeitzone ist erforderlich");
        }
        try {
            return ZoneId.of(timezone.strip());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "\"%s\" ist keine bekannte Zeitzone. Erwartet wird etwa \"Europe/Zurich\"."
                            .formatted(timezone), e);
        }
    }
}
