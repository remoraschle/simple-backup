package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Das Verzeichnis der Snapshots.
 *
 * <p>Eingetragen wird, was ein Lauf tatsaechlich erzeugt hat. Geloescht wird hier nichts von
 * selbst: Raeumt die Aufbewahrung im Repository auf, verschwinden Snapshots dort -- der
 * Abgleich passiert beim naechsten Blick ins Repository, nicht heimlich im Hintergrund.
 */
@Service
@Transactional
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final SnapshotRepository snapshots;

    SnapshotService(SnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * Haelt fest, was ein Lauf erzeugt hat.
     *
     * <p>Doppelte Eintraege sind ausgeschlossen: Dieselbe Kennung im selben Ziel ist
     * derselbe Snapshot, auch wenn ein Lauf zweimal meldet.
     */
    public void record(UUID runId, UUID planId, UUID targetId, String externalId, Long sizeBytes,
            Instant snapshotTime) {

        if (externalId == null || externalId.isBlank()) {
            return;
        }
        if (snapshots.existsByTargetIdAndExternalId(targetId, externalId)) {
            return;
        }
        snapshots.save(new Snapshot(runId, planId, targetId, externalId, sizeBytes,
                snapshotTime == null ? Instant.now() : snapshotTime));

        log.debug("Snapshot {} fuer Ziel {} vermerkt", Snapshot.shortIdOf(externalId), targetId);
    }

    @Transactional(readOnly = true)
    public List<SnapshotViews.SnapshotView> list(UUID planId, UUID targetId) {
        List<Snapshot> found;

        if (planId != null) {
            found = snapshots.findAllByPlanIdOrderBySnapshotTimeDesc(planId);
        } else if (targetId != null) {
            found = snapshots.findAllByTargetIdOrderBySnapshotTimeDesc(targetId);
        } else {
            found = snapshots.findAllByOrderBySnapshotTimeDesc();
        }
        return found.stream().map(SnapshotViews.SnapshotView::of).toList();
    }

    @Transactional(readOnly = true)
    public Snapshot require(UUID id) {
        return snapshots.findById(id)
                .orElseThrow(() -> new NotFoundException("Snapshot %s nicht gefunden".formatted(id)));
    }

    /** Nimmt einen Stand von der Aufbewahrung aus, etwa den vor einer Migration. */
    public SnapshotViews.SnapshotView pin(UUID id, boolean pinned) {
        Snapshot snapshot = require(id);
        snapshot.pin(pinned);
        return SnapshotViews.SnapshotView.of(snapshots.save(snapshot));
    }

    /**
     * Gleicht das Verzeichnis mit dem ab, was restic wirklich hat.
     *
     * <p>Was im Repository fehlt, fliegt raus; was dort steht und hier fehlt, kommt dazu.
     * Der zweite Fall ist der interessante: So tauchen auch Snapshots auf, die ein anderes
     * Werkzeug oder ein Handgriff auf der Kommandozeile angelegt hat.
     *
     * @param planId  Plan, zu dem alle gemeldeten Snapshots gehoeren, oder {@code null},
     *                wenn jeder Eintrag seinen Plan selbst mitbringt
     * @param present Kennungen und Zeitpunkte, wie restic sie meldet
     * @return wie viele Eintraege fuer dieses Ziel aufgenommen wurden
     */
    public int reconcile(UUID targetId, UUID planId, List<RepositorySnapshot> present) {
        var known = snapshots.findAllByTargetIdOrderBySnapshotTimeDesc(targetId);
        var presentIds = present.stream().map(RepositorySnapshot::id).collect(java.util.stream.Collectors.toSet());

        known.stream()
                .filter(snapshot -> !presentIds.contains(snapshot.getExternalId()))
                .forEach(snapshots::delete);

        int recorded = 0;
        for (RepositorySnapshot snapshot : present) {
            UUID owner = snapshot.planId() != null ? snapshot.planId() : planId;
            if (owner == null) {
                // Ein Repository kann Snapshots enthalten, die zu keinem Plan dieser
                // Anwendung gehoeren -- von einem geloeschten Plan, einem anderen Server
                // oder von Hand angelegt. Sie bleiben, wo sie sind; nur ins Verzeichnis
                // kommen sie nicht, denn dort haengt jeder Eintrag an einem Plan.
                continue;
            }
            record(null, owner, targetId, snapshot.id(), snapshot.sizeBytes(), snapshot.time());
            recorded++;
        }
        return recorded;
    }

    /**
     * Ein Snapshot, wie restic ihn meldet.
     *
     * @param planId Plan laut Kennzeichnung im Repository, oder {@code null}, wenn sie sich
     *               keinem hier bekannten Plan zuordnen laesst
     */
    public record RepositorySnapshot(String id, Instant time, Long sizeBytes, UUID planId) {
    }
}
