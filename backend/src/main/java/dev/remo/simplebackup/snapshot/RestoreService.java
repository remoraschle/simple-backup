package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.shared.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holt Daten aus einem Snapshot zurueck.
 *
 * <p>Der eigentliche Zweck der ganzen Anwendung. Alles davor -- Plaene, Zeitplaene,
 * Benachrichtigungen -- dient nur dazu, dass dieser Aufruf eines Tages funktioniert.
 *
 * <p>Das Zielverzeichnis muss eingehaengt sein, genau wie eine Quelle. Damit laesst sich
 * nicht an eine beliebige Stelle im Container schreiben, und ein Tippfehler im Pfad wird
 * abgelehnt statt still ins Leere zu schreiben.
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final CatalogService catalog;
    private final SnapshotService snapshots;
    private final ResticJobs jobs;
    private final ResticTargets targets;
    private final SnapshotProperties properties;

    /** Laufende und kuerzlich beendete Wiederherstellungen. */
    private final Map<UUID, RestoreJob> restores = new ConcurrentHashMap<>();

    RestoreService(CatalogService catalog, SnapshotService snapshots, ResticJobs jobs,
            ResticTargets targets, SnapshotProperties properties) {
        this.catalog = catalog;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.targets = targets;
        this.properties = properties;
    }

    /**
     * Startet eine Wiederherstellung im Hintergrund.
     *
     * @param targetPath Zielverzeichnis, aus Sicht des Backends. Muss eingehaengt sein.
     * @param includes   nur diese Pfade aus dem Snapshot, leer fuer alles
     * @return die laufende Wiederherstellung, damit der Aufrufer zusehen kann
     */
    public RestoreJob start(UUID snapshotId, String targetPath, List<String> includes) {
        Snapshot snapshot = snapshots.require(snapshotId);
        TargetView target = catalog.getTarget(snapshot.getTargetId());

        // Uebersetzt und prueft in einem: Ein nicht eingehaengter Pfad fliegt hier heraus.
        VolumeMount destination = targets.translate(targetPath, false);

        RestoreJob job = new RestoreJob(snapshotId, targetPath, includes);
        restores.put(job.getId(), job);

        Thread.ofVirtual().name("restore-" + job.getId()).start(() -> {
            try {
                var command = ResticCommands.restore(snapshot.getExternalId(), destination.target(),
                        includes);

                var result = jobs.run(target.config(), target.name(), command,
                        List.of(destination), properties.restoreTimeout(), job::append);

                job.finish(result.successful(), result.successful()
                        ? "Wiederhergestellt nach " + targetPath
                        : result.error());

                log.info("Wiederherstellung {} beendet: {}", job.getId(), job.getState());

            } catch (RuntimeException e) {
                log.error("Wiederherstellung {} gescheitert", job.getId(), e);
                job.finish(false, String.valueOf(e.getMessage()));
            }
        });

        return job;
    }

    public RestoreJob require(UUID restoreId) {
        RestoreJob job = restores.get(restoreId);
        if (job == null) {
            throw new NotFoundException("Wiederherstellung %s nicht gefunden".formatted(restoreId));
        }
        return job;
    }

    public List<RestoreJob> list() {
        return restores.values().stream()
                .sorted(java.util.Comparator.comparing(RestoreJob::getStartedAt).reversed())
                .toList();
    }

    /**
     * Holt eine einzelne Datei aus dem Snapshot in ein Arbeitsverzeichnis.
     *
     * <p>Fuer den haeufigsten Fall ueberhaupt: Eine Datei ist weg, und man braucht genau sie.
     *
     * <p>Bewusst ueber {@code restore} in eine Datei und nicht ueber {@code dump} auf die
     * Standardausgabe: Die Ausgabe des Runners kommt zeilenweise als Text an: Ein Bild oder
     * ein Archiv kaeme so beschaedigt heraus -- und ausgerechnet bei einer Wiederherstellung
     * faellt das erst auf, wenn man die Datei braucht.
     *
     * @return Pfad der zurueckgeholten Datei. Der Aufrufer loescht sie nach dem Ausliefern.
     */
    public Path fetchSingleFile(UUID snapshotId, String path) {
        Snapshot snapshot = snapshots.require(snapshotId);
        TargetView target = catalog.getTarget(snapshot.getTargetId());

        Path staging = createStagingDirectory();
        VolumeMount destination = targets.translate(staging.toString(), false);

        var command = ResticCommands.restore(snapshot.getExternalId(), destination.target(),
                List.of(path));

        var result = jobs.run(target.config(), target.name(), command, List.of(destination),
                properties.dumpTimeout(), line -> { });

        if (!result.successful()) {
            deleteRecursively(staging);
            throw new NotFoundException("Die Datei liess sich nicht zurueckholen: " + result.error());
        }

        // restic legt den vollstaendigen Pfad unterhalb des Zielverzeichnisses an.
        Path restored = staging.resolve(path.startsWith("/") ? path.substring(1) : path);

        if (!Files.isRegularFile(restored)) {
            deleteRecursively(staging);
            throw new NotFoundException("Im Snapshot gibt es keine Datei unter " + path);
        }
        return restored;
    }

    /** Raeumt das Arbeitsverzeichnis einer einzelnen Datei wieder ab. */
    public void discard(Path restoredFile) {
        Path staging = restoredFile;
        Path root = Path.of(properties.stagingDirectory());

        while (staging != null && staging.getParent() != null && !staging.getParent().equals(root)) {
            staging = staging.getParent();
        }
        if (staging != null) {
            deleteRecursively(staging);
        }
    }

    private Path createStagingDirectory() {
        try {
            Path staging = Path.of(properties.stagingDirectory(), "download-" + UUID.randomUUID());
            Files.createDirectories(staging);
            return staging;
        } catch (IOException e) {
            throw new IllegalStateException("Arbeitsverzeichnis liess sich nicht anlegen", e);
        }
    }

    private static void deleteRecursively(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    log.warn("Konnte {} nicht loeschen", entry, e);
                }
            });
        } catch (IOException e) {
            log.warn("Konnte Arbeitsverzeichnis {} nicht aufraeumen", path, e);
        }
    }
}
