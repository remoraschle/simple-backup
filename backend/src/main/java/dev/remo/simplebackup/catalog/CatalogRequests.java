package dev.remo.simplebackup.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Was die API entgegennimmt. */
public final class CatalogRequests {

    /** Buchstaben, Ziffern und wenige Trennzeichen -- Namen erscheinen in Container-Namen. */
    private static final String NAME_PATTERN = "[A-Za-z0-9 _.äöüÄÖÜß-]+";

    private CatalogRequests() {
    }

    public record SaveSource(
            @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 500) String description,
            @NotNull @Valid SourceConfig config) {
    }

    public record SaveTarget(
            @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 500) String description,
            @NotNull TargetMode mode,
            @NotNull @Valid TargetConfig config,
            boolean enabled) {
    }

    public record SavePlan(
            @NotBlank @Size(max = 100) @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 500) String description,
            @NotNull UUID sourceId,
            @NotEmpty(message = "Ein Plan braucht mindestens ein Ziel") List<UUID> targetIds,
            UUID retentionPolicyId,
            @NotBlank String cronExpression,
            @NotBlank String timezone,
            boolean enabled,
            @Min(1) @Max(10080) int timeoutMinutes,
            @Min(0) @Max(10) int maxRetries,
            @NotNull MissedRunPolicy missedRunPolicy,
            @NotNull NotifyOn notifyOn,
            @Min(1) Integer expectedIntervalMinutes) {
    }

    public record SaveRetentionPolicy(
            @NotBlank @Size(max = 100) String name,
            @Min(0) Integer keepLast,
            @Min(0) Integer keepHourly,
            @Min(0) Integer keepDaily,
            @Min(0) Integer keepWeekly,
            @Min(0) Integer keepMonthly,
            @Min(0) Integer keepYearly,
            @Min(0) Integer keepWithinDays) {
    }
}
