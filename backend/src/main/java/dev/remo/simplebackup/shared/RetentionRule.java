package dev.remo.simplebackup.shared;

/**
 * Grossvater-Vater-Sohn-Regel fuer die Aufbewahrung.
 *
 * <p>Ein {@code null}-Wert bedeutet, dass diese Stufe nicht beruecksichtigt wird.
 *
 * @param keepWithinDays alles juenger als so viele Tage bleibt, unabhaengig von den Stufen
 */
public record RetentionRule(
        Integer keepLast,
        Integer keepHourly,
        Integer keepDaily,
        Integer keepWeekly,
        Integer keepMonthly,
        Integer keepYearly,
        Integer keepWithinDays) {

    public RetentionRule {
        if (allEmpty(keepLast, keepHourly, keepDaily, keepWeekly, keepMonthly, keepYearly, keepWithinDays)) {
            throw new IllegalArgumentException("""
                    Eine Aufbewahrungsregel, die nichts behaelt, wuerde beim ersten Prune saemtliche \
                    Snapshots loeschen. Mindestens eine Stufe muss gesetzt sein.""");
        }
    }

    private static boolean allEmpty(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return false;
            }
        }
        return true;
    }

    /** Ein gebraeuchlicher Ausgangspunkt. */
    public static RetentionRule sensibleDefault() {
        return new RetentionRule(null, null, 7, 4, 12, 3, null);
    }
}
