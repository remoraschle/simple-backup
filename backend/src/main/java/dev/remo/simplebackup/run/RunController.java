package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.security.AuditService;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
class RunController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RunService runService;
    private final CatalogService catalog;
    private final AuditService audit;

    RunController(RunService runService, CatalogService catalog, AuditService audit) {
        this.runService = runService;
        this.catalog = catalog;
        this.audit = audit;
    }

    @GetMapping
    Page<RunViews.RunSummary> list(
            @RequestParam(required = false) UUID planId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        Page<BackupRun> runs = runService.findRuns(planId,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));

        Map<UUID, String> names = catalog.planNames(
                runs.getContent().stream().map(BackupRun::getPlanId).collect(Collectors.toSet()));

        return runs.map(run -> RunViews.RunSummary.of(run, names.get(run.getPlanId())));
    }

    @GetMapping("/{id}")
    RunViews.RunDetail get(@PathVariable UUID id) {
        BackupRun run = runService.findRun(id);
        return RunViews.RunDetail.of(run, catalog.planNames(Set.of(run.getPlanId())).get(run.getPlanId()));
    }

    /**
     * Verfolgt einen laufenden Lauf in Echtzeit.
     *
     * <p>Server-Sent Events: Der Datenstrom geht nur in eine Richtung, und Wiederverbindung
     * bringt der Browser von selbst mit. Ein WebSocket waere mehr Technik fuer dieselbe
     * Aufgabe.
     *
     * <p>Der vorgelagerte nginx muss fuer diesen Pfad die Pufferung abschalten -- sonst
     * erschiene die Ausgabe erst am Ende des Laufs, also genau dann nicht, wenn man sie
     * braucht.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID id) {
        return runService.streamEvents(id);
    }

    /** Das Protokoll als Klartext -- es ist bereits von Geheimnissen bereinigt. */
    @GetMapping(value = "/{id}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    String log(@PathVariable UUID id) {
        return runService.readLog(id);
    }

    /** Startet einen Plan sofort, unabhaengig vom Zeitplan. */
    @PostMapping("/start")
    ResponseEntity<Map<String, String>> start(@RequestParam UUID planId, Principal principal) {
        audit.record(principal.getName(), "RUN_STARTED_MANUALLY", "backup_plan", planId.toString(), null);

        return runService.startRun(planId, RunTrigger.MANUAL)
                .map(runId -> ResponseEntity.accepted().body(Map.of("runId", runId.toString())))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of("message", "Für diesen Plan läuft bereits eine Sicherung")));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Void> cancel(@PathVariable UUID id, Principal principal) {
        runService.cancelRun(id);
        audit.record(principal.getName(), "RUN_CANCELLED", "backup_run", id.toString(), null);
        return ResponseEntity.accepted().build();
    }
}
