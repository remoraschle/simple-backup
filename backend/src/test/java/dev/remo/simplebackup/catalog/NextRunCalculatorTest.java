package dev.remo.simplebackup.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NextRunCalculatorTest {

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final String NIGHTLY_AT_TWO = "0 0 2 * * *";

    private final NextRunCalculator calculator = new NextRunCalculator();

    private static Instant zurich(String isoLocalDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocalDateTime), ZURICH).toInstant();
    }

    @Nested
    @DisplayName("Berechnung")
    class Calculation {

        @Test
        @DisplayName("Der naechste Termin liegt am Folgetag, wenn der heutige vorbei ist")
        void findsNextDay() {
            var next = calculator.nextAfter(NIGHTLY_AT_TWO, "Europe/Zurich",
                    zurich("2026-03-10T08:00:00")).orElseThrow();

            assertThat(next).isEqualTo(zurich("2026-03-11T02:00:00"));
        }

        @Test
        @DisplayName("Der heutige Termin gilt, solange er noch bevorsteht")
        void findsSameDay() {
            var next = calculator.nextAfter(NIGHTLY_AT_TWO, "Europe/Zurich",
                    zurich("2026-03-10T01:00:00")).orElseThrow();

            assertThat(next).isEqualTo(zurich("2026-03-10T02:00:00"));
        }

        @Test
        @DisplayName("Nach der Umstellung auf Sommerzeit laeuft es weiter um 2 Uhr Ortszeit")
        void survivesSpringForward() {
            // Am 29.03.2026 springt die Uhr in der Schweiz von 2 auf 3 Uhr. Ein Backup, das
            // "jede Nacht um 2 Uhr" laufen soll, muss danach wieder um 2 Uhr Ortszeit
            // laufen -- in UTC gerechnet waere es dauerhaft eine Stunde verschoben.
            var next = calculator.nextAfter(NIGHTLY_AT_TWO, "Europe/Zurich",
                    zurich("2026-03-29T12:00:00")).orElseThrow();

            var localTime = next.atZone(ZURICH);
            assertThat(localTime.getHour()).isEqualTo(2);
            assertThat(localTime.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 30));
        }

        @Test
        @DisplayName("Nach der Umstellung auf Winterzeit ebenso")
        void survivesFallBack() {
            var next = calculator.nextAfter(NIGHTLY_AT_TWO, "Europe/Zurich",
                    zurich("2026-10-25T12:00:00")).orElseThrow();

            assertThat(next.atZone(ZURICH).getHour()).isEqualTo(2);
        }

        @Test
        @DisplayName("Verschiedene Zeitzonen ergeben verschiedene Zeitpunkte")
        void respectsTimezone() {
            var after = Instant.parse("2026-06-15T00:00:00Z");

            var inZurich = calculator.nextAfter(NIGHTLY_AT_TWO, "Europe/Zurich", after).orElseThrow();
            var inTokyo = calculator.nextAfter(NIGHTLY_AT_TWO, "Asia/Tokyo", after).orElseThrow();

            assertThat(inZurich).isNotEqualTo(inTokyo);
            assertThat(inZurich.atZone(ZURICH).getHour()).isEqualTo(2);
            assertThat(inTokyo.atZone(ZoneId.of("Asia/Tokyo")).getHour()).isEqualTo(2);
        }

        @Test
        @DisplayName("Ein woechentlicher Zeitplan trifft den richtigen Wochentag")
        void handlesWeeklySchedule() {
            var next = calculator.nextAfter("0 30 3 * * SUN", "Europe/Zurich",
                    zurich("2026-09-16T12:00:00")).orElseThrow();

            assertThat(next.atZone(ZURICH).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
            assertThat(next.atZone(ZURICH).getHour()).isEqualTo(3);
            assertThat(next.atZone(ZURICH).getMinute()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("Nach einem Ausfall")
    class AfterDowntime {

        @Test
        @DisplayName("SKIP wartet auf den naechsten regulaeren Termin")
        void skipWaitsForNextRegularRun() {
            // Nach einem laengeren Ausfall starteten sonst alle verpassten Laeufe auf einmal
            // und legten den Server lahm.
            var missed = zurich("2026-03-05T02:00:00");
            var now = zurich("2026-03-10T08:00:00");

            var next = calculator.resolveAfterDowntime(NIGHTLY_AT_TWO, "Europe/Zurich",
                    missed, now, MissedRunPolicy.SKIP).orElseThrow();

            assertThat(next).isEqualTo(zurich("2026-03-11T02:00:00"));
        }

        @Test
        @DisplayName("CATCH_UP holt genau einen Lauf nach, nicht alle verpassten")
        void catchUpRunsOnce() {
            var missed = zurich("2026-03-05T02:00:00");
            var now = zurich("2026-03-10T08:00:00");

            var next = calculator.resolveAfterDowntime(NIGHTLY_AT_TWO, "Europe/Zurich",
                    missed, now, MissedRunPolicy.CATCH_UP).orElseThrow();

            // Der verpasste Termin bleibt faellig und wird sofort ausgefuehrt.
            assertThat(next).isEqualTo(missed);
        }

        @Test
        @DisplayName("Ein noch bevorstehender Termin bleibt unberuehrt")
        void futureScheduleIsRecalculated() {
            var future = zurich("2026-03-11T02:00:00");
            var now = zurich("2026-03-10T08:00:00");

            var next = calculator.resolveAfterDowntime(NIGHTLY_AT_TWO, "Europe/Zurich",
                    future, now, MissedRunPolicy.SKIP).orElseThrow();

            assertThat(next).isEqualTo(future);
        }

        @Test
        @DisplayName("Ein neuer Plan bekommt seinen ersten Termin")
        void newPlanGetsFirstRun() {
            var next = calculator.resolveAfterDowntime(NIGHTLY_AT_TWO, "Europe/Zurich",
                    null, zurich("2026-03-10T08:00:00"), MissedRunPolicy.SKIP).orElseThrow();

            assertThat(next).isEqualTo(zurich("2026-03-11T02:00:00"));
        }
    }

    @Nested
    @DisplayName("Pruefung")
    class Validation {

        @Test
        @DisplayName("Ein unsinniger Zeitplan wird mit Beispielen erklaert")
        void explainsInvalidCron() {
            assertThatThrownBy(() -> calculator.validate("jeden tag", "Europe/Zurich"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sechs Felder")
                    .hasMessageContaining("0 0 2 * * *");
        }

        @Test
        @DisplayName("Eine unbekannte Zeitzone wird abgelehnt")
        void rejectsUnknownTimezone() {
            assertThatThrownBy(() -> calculator.validate(NIGHTLY_AT_TWO, "Europe/Musterstadt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Europe/Zurich");
        }

        @Test
        @DisplayName("Ein gueltiger Zeitplan wird angenommen")
        void acceptsValidSchedule() {
            calculator.validate(NIGHTLY_AT_TWO, "Europe/Zurich");
            calculator.validate("0 */15 * * * *", "UTC");
        }

        @Test
        @DisplayName("Leere Angaben werden abgelehnt")
        void rejectsBlankInput() {
            assertThatThrownBy(() -> calculator.validate("", "Europe/Zurich"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.validate(NIGHTLY_AT_TWO, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
