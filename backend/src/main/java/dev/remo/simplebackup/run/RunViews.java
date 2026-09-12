package dev.remo.simplebackup.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Was die API ueber Laeufe herausgibt. */
public final class RunViews {

    private RunViews() {
    }

    public record RunSummary(
            UUID id,
            UUID planId,
            RunStatus status,
            RunTrigger trigger,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Long durationSeconds,
            Long bytesProcessed,
            Long bytesTransferred,
            Long filesNew,
            Long filesChanged,
            String errorSummary) {

        static RunSummary of(BackupRun run) {
            return new RunSummary(run.getId(), run.getPlanId(), run.getStatus(), run.getTriggerType(),
                    run.getQueuedAt(), run.getStartedAt(), run.getFinishedAt(),
                    run.getStartedAt() == null ? null : run.getDuration().toSeconds(),
                    run.getBytesProcessed(), run.getBytesTransferred(), run.getFilesNew(),
                    run.getFilesChanged(), run.getErrorSummary());
        }
    }

    public record RunDetail(RunSummary summary, List<StepView> steps) {

        static RunDetail of(BackupRun run) {
            return new RunDetail(RunSummary.of(run), run.getSteps().stream().map(StepView::of).toList());
        }
    }

    public record StepView(
            UUID id,
            int seq,
            StepKind kind,
            UUID targetId,
            StepStatus status,
            Integer exitCode,
            Instant startedAt,
            Instant finishedAt,
            String message,
            List<String> command) {

        static StepView of(RunStep step) {
            return new StepView(step.getId(), step.getSeq(), step.getKind(), step.getTargetId(),
                    step.getStatus(), step.getExitCode(), step.getStartedAt(), step.getFinishedAt(),
                    step.getMessage(), step.getCommand());
        }
    }
}
