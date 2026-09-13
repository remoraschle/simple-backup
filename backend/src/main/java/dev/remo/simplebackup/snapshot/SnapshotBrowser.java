package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.restic.ResticCommands;
import dev.remo.simplebackup.restic.ResticListing;
import dev.remo.simplebackup.restic.ResticListingParser;
import dev.remo.simplebackup.shared.NotFoundException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Blaettern im Snapshot und Abgleich mit dem Repository.
 *
 * <p>Gelesen wird immer aus dem Repository, nicht aus der Datenbank: Was hier angezeigt
 * wird, muss genau das sein, was sich auch zurueckholen laesst. Ein Verzeichnis, das von der
 * Wirklichkeit abweicht, waere schlimmer als keines.
 */
@Service
public class SnapshotBrowser {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBrowser.class);

    /** Auflisten ist billig, aber ein grosses Repository braucht trotzdem Zeit. */
    private static final Duration LIST_TIMEOUT = Duration.ofMinutes(10);

    private final CatalogService catalog;
    private final SnapshotService snapshots;
    private final ResticJobs jobs;
    private final ResticListingParser parser;

    SnapshotBrowser(CatalogService catalog, SnapshotService snapshots, ResticJobs jobs,
            ResticListingParser parser) {
        this.catalog = catalog;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.parser = parser;
    }

    /**
     * Fragt das Repository, welche Snapshots es wirklich gibt, und gleicht das Verzeichnis ab.
     *
     * @param planId nur die Snapshots dieses Plans, oder {@code null} fuer alle im Ziel
     */
    public List<SnapshotViews.SnapshotView> refresh(UUID targetId, UUID planId) {
        TargetView target = catalog.getTarget(targetId);

        // Host und Tag kommen vom Plan selbst und werden hier nicht nachgebaut: Eine zweite
        // Herleitung derselben Kennung liefe irgendwann auseinander, und dann faende die
        // Wiederherstellung nichts mehr.
        var plan = planId == null ? null : catalog.toExecutable(planId);

        var command = plan == null
                ? ResticCommands.snapshots(null, null)
                : ResticCommands.snapshots(plan.resticHost(), plan.resticTag());

        var result = jobs.run(target.config(), target.name(), command, LIST_TIMEOUT);

        if (!result.successful()) {
            throw new IllegalStateException("Das Repository liess sich nicht lesen: " + result.error());
        }

        List<ResticListing.Snapshot> present = parser.parseSnapshots(result.joined());
        log.info("Ziel {} meldet {} Snapshots", target.name(), present.size());

        snapshots.reconcile(targetId, planId, present.stream()
                .map(snapshot -> new SnapshotService.RepositorySnapshot(snapshot.id(), snapshot.time(), null))
                .toList());

        return snapshots.list(planId, planId == null ? targetId : null);
    }

    /**
     * Listet den Inhalt eines Snapshots an einer Stelle.
     *
     * <p>Nur die unmittelbaren Kinder, nicht der ganze Teilbaum: Ein Snapshot mit
     * Hunderttausenden Dateien wuerde sonst weder durchs Netz passen noch in eine Anzeige.
     *
     * @param path Verzeichnis im Snapshot, {@code null} oder {@code /} fuer die Wurzel
     */
    public SnapshotViews.BrowseResult browse(UUID snapshotId, String path) {
        Snapshot snapshot = snapshots.require(snapshotId);
        TargetView target = catalog.getTarget(snapshot.getTargetId());

        String directory = normalize(path);
        var result = jobs.run(target.config(), target.name(),
                ResticCommands.list(snapshot.getExternalId(), directory), LIST_TIMEOUT);

        if (!result.successful()) {
            throw new NotFoundException("Der Snapshot liess sich nicht lesen: " + result.error());
        }

        int depth = directory.equals("/") ? 1 : depthOf(directory) + 1;

        List<SnapshotViews.EntryView> entries = parser.parseNodes(result.joined()).stream()
                .filter(node -> node.depth() == depth)
                .filter(node -> directory.equals("/") || node.path().startsWith(directory + "/"))
                .sorted(Comparator.comparing(ResticListing.Node::directory).reversed()
                        .thenComparing(ResticListing.Node::path))
                .map(node -> new SnapshotViews.EntryView(node.path(), node.name(), node.directory(),
                        node.size(), node.modified()))
                .toList();

        return new SnapshotViews.BrowseResult(snapshot.getExternalId(), directory, entries);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "/";
        }
        String trimmed = path.startsWith("/") ? path : "/" + path;
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static int depthOf(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }
}
