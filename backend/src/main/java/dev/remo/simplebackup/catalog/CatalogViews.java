package dev.remo.simplebackup.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Was die API ueber den Katalog herausgibt. */
public final class CatalogViews {

    private CatalogViews() {
    }

    /** @param lastCheck Ergebnis der letzten Erreichbarkeitspruefung, oder {@code null} */
    public record SourceView(
            UUID id,
            String name,
            SourceType type,
            String description,
            SourceConfig config,
            CheckResult lastCheck,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TargetView(
            UUID id,
            String name,
            TargetType type,
            TargetMode mode,
            String description,
            TargetConfig config,
            boolean enabled,
            CheckResult lastCheck,
            Capacity capacity,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CheckResult(Instant at, boolean successful, String message) {
    }

    /** Freier Platz ist der haeufigste Grund fuer ploetzlich fehlschlagende Backups. */
    public record Capacity(Long totalBytes, Long freeBytes, Long usedBytes) {

        public Integer usedPercent() {
            if (totalBytes == null || totalBytes == 0 || usedBytes == null) {
                return null;
            }
            return (int) Math.round(usedBytes * 100.0 / totalBytes);
        }
    }

    public record PlanView(
            UUID id,
            String name,
            String description,
            UUID sourceId,
            String sourceName,
            List<TargetReference> targets,
            UUID retentionPolicyId,
            String cronExpression,
            String timezone,
            boolean enabled,
            int timeoutMinutes,
            int maxRetries,
            MissedRunPolicy missedRunPolicy,
            NotifyOn notifyOn,
            Integer expectedIntervalMinutes,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastRunStatus,
            Instant createdAt) {
    }

    public record TargetReference(UUID id, String name, TargetMode mode) {
    }

    /**
     * Ob sich ein Quelltyp hier anlegen laesst.
     *
     * @param unavailableReason in ganzen Saetzen, oder {@code null}. Ein ausgegrauter
     *                          Eintrag ohne Begruendung ist eine Sackgasse.
     */
    public record SourceTypeView(SourceType type, boolean available, String unavailableReason) {
    }
}
