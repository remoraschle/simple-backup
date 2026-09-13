package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.security.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/snapshots")
class SnapshotController {

    private final SnapshotService snapshots;
    private final SnapshotBrowser browser;
    private final RestoreService restores;
    private final IntegrityService integrity;
    private final AuditService audit;

    SnapshotController(SnapshotService snapshots, SnapshotBrowser browser, RestoreService restores,
            IntegrityService integrity, AuditService audit) {
        this.snapshots = snapshots;
        this.browser = browser;
        this.restores = restores;
        this.integrity = integrity;
        this.audit = audit;
    }

    @GetMapping
    List<SnapshotViews.SnapshotView> list(@RequestParam(required = false) UUID planId,
            @RequestParam(required = false) UUID targetId) {
        return snapshots.list(planId, targetId);
    }

    /** Fragt das Repository selbst und gleicht das Verzeichnis ab. */
    @PostMapping("/refresh")
    List<SnapshotViews.SnapshotView> refresh(@RequestParam UUID targetId,
            @RequestParam(required = false) UUID planId) {
        return browser.refresh(targetId, planId);
    }

    @GetMapping("/{id}/entries")
    SnapshotViews.BrowseResult browse(@PathVariable UUID id,
            @RequestParam(required = false) String path) {
        return browser.browse(id, path);
    }

    @PostMapping("/{id}/pin")
    SnapshotViews.SnapshotView pin(@PathVariable UUID id, @RequestParam boolean pinned,
            Principal principal) {

        audit.record(principal.getName(), pinned ? "SNAPSHOT_PINNED" : "SNAPSHOT_UNPINNED",
                "snapshot", id.toString(), null);
        return snapshots.pin(id, pinned);
    }

    /**
     * Holt eine einzelne Datei zurueck und liefert sie aus.
     *
     * <p>Als Datenstrom und nicht als Zeichenkette: Ein Bild oder ein Archiv wuerde sonst
     * beschaedigt ankommen -- und ausgerechnet bei einer Wiederherstellung faellt das erst
     * auf, wenn man die Datei braucht.
     */
    @GetMapping("/{id}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable UUID id, @RequestParam String path,
            Principal principal) throws IOException {

        audit.record(principal.getName(), "FILE_DOWNLOADED", "snapshot", id.toString(), path);

        Path restored = restores.fetchSingleFile(id, path);
        long size = Files.size(restored);

        InputStream stream = new java.io.FilterInputStream(Files.newInputStream(restored)) {
            @Override
            public void close() throws IOException {
                super.close();
                restores.discard(restored);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s\"".formatted(restored.getFileName()))
                .contentLength(size)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    // -------------------------------------------------------- Wiederherstellung

    @PostMapping("/restores")
    ResponseEntity<RestoreViews.RestoreView> startRestore(@Valid @RequestBody StartRestore request,
            Principal principal) {

        audit.record(principal.getName(), "RESTORE_STARTED", "snapshot",
                request.snapshotId().toString(), request.targetPath());

        RestoreJob job = restores.start(request.snapshotId(), request.targetPath(),
                request.includes() == null ? List.of() : request.includes());

        return ResponseEntity.accepted().body(RestoreViews.RestoreView.of(job));
    }

    @GetMapping("/restores")
    List<RestoreViews.RestoreView> listRestores() {
        return restores.list().stream().map(RestoreViews.RestoreView::of).toList();
    }

    @GetMapping("/restores/{id}")
    RestoreViews.RestoreView restore(@PathVariable UUID id) {
        return RestoreViews.RestoreView.of(restores.require(id));
    }

    @GetMapping("/restores/{id}/log")
    Map<String, Object> restoreLog(@PathVariable UUID id) {
        RestoreJob job = restores.require(id);
        return Map.of("state", job.getState().name(), "lines", job.getLog());
    }

    // ------------------------------------------------------------------ Pruefung

    @PostMapping("/check")
    IntegrityService.CheckOutcome check(@RequestParam UUID targetId,
            @RequestParam(required = false) Integer readDataPercent, Principal principal) {

        audit.record(principal.getName(), "INTEGRITY_CHECK", "backup_target", targetId.toString(),
                readDataPercent == null ? null : readDataPercent + "%");

        return integrity.check(targetId, readDataPercent);
    }

    @PostMapping("/verify")
    IntegrityService.RestoreTestOutcome verify(@RequestParam UUID targetId, Principal principal) {
        audit.record(principal.getName(), "RESTORE_TEST", "backup_target", targetId.toString(), null);
        return integrity.verifyByRestoringOneFile(targetId);
    }

    /**
     * @param targetPath Zielverzeichnis, aus Sicht des Backends. Muss eingehaengt sein.
     * @param includes   nur diese Pfade aus dem Snapshot, leer fuer alles
     */
    record StartRestore(
            @NotNull UUID snapshotId,
            @NotBlank String targetPath,
            List<String> includes) {
    }
}
