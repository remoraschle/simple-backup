package dev.remo.simplebackup.run;

import dev.remo.simplebackup.restic.ResticMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Haelt fest, was der Runner meldet, damit die Tests es pruefen koennen. */
class RecordingProgressListener implements RunProgressListener {

    record Step(UUID id, StepKind kind, UUID targetId, String description, List<String> command) {
    }

    final List<Step> started = new ArrayList<>();
    final Map<UUID, StepStatus> finished = new LinkedHashMap<>();
    final Map<UUID, String> messages = new LinkedHashMap<>();
    final List<String> logLines = new ArrayList<>();
    final List<ResticMessage.Progress> progressUpdates = new ArrayList<>();

    @Override
    public UUID stepStarted(StepKind kind, UUID targetId, String description, String image,
            List<String> redactedCommand) {
        UUID id = UUID.randomUUID();
        started.add(new Step(id, kind, targetId, description, redactedCommand));
        return id;
    }

    @Override
    public void stepFinished(UUID stepId, StepStatus status, Integer exitCode, String message) {
        finished.put(stepId, status);
        messages.put(stepId, message);
    }

    @Override
    public void progress(UUID stepId, ResticMessage.Progress progress) {
        progressUpdates.add(progress);
    }

    @Override
    public void logLine(String line) {
        logLines.add(line);
    }

    List<StepKind> stepKinds() {
        return started.stream().map(Step::kind).toList();
    }

    List<String> descriptions() {
        return started.stream().map(Step::description).toList();
    }
}
