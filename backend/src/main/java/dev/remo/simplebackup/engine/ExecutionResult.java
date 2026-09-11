package dev.remo.simplebackup.engine;

import java.time.Duration;
import java.time.Instant;

/**
 * Ergebnis eines beendeten Schritts.
 *
 * @param executionId  Kennung aus der Anfrage
 * @param status       wie der Schritt geendet hat
 * @param exitCode     Rueckgabewert; {@code null}, wenn der Prozess nie beendet wurde
 * @param startedAt    Beginn
 * @param finishedAt   Ende
 * @param lastError    letzte Fehlerzeile, bereits von Geheimnissen bereinigt
 */
public record ExecutionResult(
        String executionId,
        ExecutionStatus status,
        Integer exitCode,
        Instant startedAt,
        Instant finishedAt,
        String lastError) {

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public boolean isSuccess() {
        return status.isSuccess();
    }

    /** Fuer Implementierungen von {@link BackupExecutor}. */
    public static ExecutionResult of(String executionId, int exitCode, Instant startedAt, String lastError) {
        return new ExecutionResult(
                executionId,
                exitCode == 0 ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED,
                exitCode,
                startedAt,
                Instant.now(),
                exitCode == 0 ? null : lastError);
    }

    /** Fuer Implementierungen von {@link BackupExecutor}: Ende ohne regulaeren Rueckgabewert. */
    public static ExecutionResult terminated(String executionId, ExecutionStatus status, Instant startedAt,
            String message) {
        return new ExecutionResult(executionId, status, null, startedAt, Instant.now(), message);
    }
}
