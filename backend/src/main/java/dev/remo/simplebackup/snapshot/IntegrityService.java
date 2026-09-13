package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.notification.Notification;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.Severity;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.restic.ResticListing;
import dev.remo.simplebackup.restic.ResticListingParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prueft, ob die Sicherungen noch etwas taugen.
 *
 * <p>Zwei Stufen, die verschiedene Fragen beantworten:
 *
 * <ul>
 *   <li>{@code restic check} prueft die Struktur und -- mit Anteil -- auch die Daten selbst.
 *       Damit faellt auf, wenn am Ziel etwas verrottet ist.
 *   <li>Der Stichproben-Restore holt eine echte Datei zurueck. Das ist die einzige Probe,
 *       die die Frage beantwortet, auf die es ankommt: Kommt im Ernstfall etwas Brauchbares
 *       heraus? Ein Backup, das nie wiederhergestellt wurde, ist eine Vermutung.
 * </ul>
 */
@Service
public class IntegrityService {

    private static final Logger log = LoggerFactory.getLogger(IntegrityService.class);

    static final String EVENT_TYPE = "INTEGRITY_CHECK_FAILED";

    private final CatalogService catalog;
    private final SnapshotService snapshots;
    private final ResticJobs jobs;
    private final ResticListingParser parser;
    private final NotificationService notifications;
    private final RestoreService restoreService;
    private final SnapshotProperties properties;

    IntegrityService(CatalogService catalog, SnapshotService snapshots, ResticJobs jobs,
            ResticListingParser parser, NotificationService notifications,
            RestoreService restoreService, SnapshotProperties properties) {

        this.catalog = catalog;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.parser = parser;
        this.notifications = notifications;
        this.restoreService = restoreService;
        this.properties = properties;
    }

    /**
     * Prueft ein Ziel.
     *
     * @param readDataPercent Anteil der Daten, der wirklich gelesen wird. {@code null} prueft
     *                        nur die Struktur -- schnell, aber es bliebe unbemerkt, wenn am
     *                        Ziel Daten verrottet sind.
     */
    public CheckOutcome check(UUID targetId, Integer readDataPercent) {
        TargetView target = catalog.getTarget(targetId);

        var result = jobs.run(target.config(), target.name(),
                ResticCommands.check(readDataPercent), properties.checkTimeout());

        var outcome = new CheckOutcome(targetId, target.name(), result.successful(),
                result.successful() ? "Repository ist unversehrt" : shorten(result.error()),
                Instant.now());

        if (!result.successful()) {
            log.error("Pruefung von {} fehlgeschlagen: {}", target.name(), outcome.message());
            alert("Repository beschädigt: " + target.name(), outcome.message(), targetId);
        }
        return outcome;
    }

    /**
     * Holt eine echte Datei aus dem neuesten Snapshot zurueck und vergleicht sie.
     *
     * <p>Verglichen wird gegen das Original an seinem Platz. Existiert es nicht mehr --
     * geloescht, umbenannt, verschoben --, gilt die Probe als bestanden, sobald etwas
     * Lesbares zurueckkam: Ein Unterschied waere dann kein Befund ueber das Backup.
     */
    public RestoreTestOutcome verifyByRestoringOneFile(UUID targetId) {
        TargetView target = catalog.getTarget(targetId);

        List<SnapshotViews.SnapshotView> known = snapshots.list(null, targetId);
        if (known.isEmpty()) {
            return new RestoreTestOutcome(targetId, target.name(), false, null,
                    "Es gibt noch keinen Snapshot, aus dem sich etwas zurueckholen liesse",
                    Instant.now());
        }

        SnapshotViews.SnapshotView newest = known.getFirst();
        String candidate = pickFile(target, newest.externalId());

        if (candidate == null) {
            return new RestoreTestOutcome(targetId, target.name(), false, null,
                    "Im neuesten Snapshot ist keine Datei zu finden", Instant.now());
        }

        try {
            Path restored = restoreService.fetchSingleFile(newest.id(), candidate);
            try {
                boolean matches = compareWithOriginal(candidate, restored);
                String message = matches
                        ? "Stichprobe zurueckgeholt und geprueft: " + candidate
                        : "Die zurueckgeholte Datei weicht vom Original ab: " + candidate;

                if (!matches) {
                    alert("Stichprobe weicht ab: " + target.name(), message, targetId);
                }
                return new RestoreTestOutcome(targetId, target.name(), matches, candidate, message,
                        Instant.now());
            } finally {
                restoreService.discard(restored);
            }
        } catch (RuntimeException e) {
            String message = "Die Stichprobe liess sich nicht zurueckholen: " + e.getMessage();
            alert("Stichprobe fehlgeschlagen: " + target.name(), message, targetId);
            return new RestoreTestOutcome(targetId, target.name(), false, candidate, message,
                    Instant.now());
        }
    }

    /** Die erste Datei im Snapshot -- gross genug, dass ein Vergleich etwas aussagt. */
    private String pickFile(TargetView target, String snapshotExternalId) {
        var result = jobs.run(target.config(), target.name(),
                ResticCommands.list(snapshotExternalId, null), properties.dumpTimeout());

        if (!result.successful()) {
            return null;
        }
        return parser.parseNodes(result.joined()).stream()
                .filter(node -> !node.directory())
                .filter(node -> node.size() != null && node.size() > 0)
                .map(ResticListing.Node::path)
                .findFirst()
                .orElse(null);
    }

    private boolean compareWithOriginal(String path, Path restored) {
        Path original = Path.of(path);
        try {
            if (!Files.isRegularFile(original)) {
                // Das Original gibt es nicht mehr. Dass etwas Lesbares zurueckkam, ist dann
                // die Aussage, um die es geht.
                return Files.size(restored) > 0;
            }
            return Files.mismatch(original, restored) == -1L;
        } catch (IOException e) {
            log.warn("Vergleich mit dem Original {} fehlgeschlagen", path, e);
            return false;
        }
    }

    private void alert(String title, String message, UUID targetId) {
        notifications.publish(new Notification(EVENT_TYPE, Severity.CRITICAL, title, message,
                null, null, Map.of("targetId", targetId.toString())));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "Ohne Meldung";
        }
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }

    /** Ergebnis von {@code restic check}. */
    public record CheckOutcome(UUID targetId, String targetName, boolean successful, String message,
            Instant checkedAt) {
    }

    /** Ergebnis der Stichprobe. */
    public record RestoreTestOutcome(UUID targetId, String targetName, boolean successful,
            String path, String message, Instant checkedAt) {
    }
}
