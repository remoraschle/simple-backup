package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews.TargetView;
import dev.remo.simplebackup.catalog.TargetMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prueft die Ziele regelmaessig von selbst.
 *
 * <p>Der Grund fuer den festen Termin: Eine Pruefung, die man von Hand anstossen muss, wird
 * genau einmal angestossen -- am Tag der Einrichtung. Danach nie wieder, und der stille
 * Verfall am Ziel faellt erst auf, wenn man die Daten braucht.
 *
 * <p>Standardmaessig sonntags nachts, wenn ohnehin wenig laeuft. Gelesen wird nur ein
 * Bruchteil der Daten: Alles zu lesen dauert bei grossen Repositories Stunden und kostet bei
 * S3 bares Geld -- ein Stichprobenanteil findet Verfall trotzdem, nur eben spaeter.
 */
@Component
class IntegrityScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityScheduler.class);

    private final CatalogService catalog;
    private final IntegrityService integrity;
    private final SnapshotProperties properties;

    IntegrityScheduler(CatalogService catalog, IntegrityService integrity,
            SnapshotProperties properties) {
        this.catalog = catalog;
        this.integrity = integrity;
        this.properties = properties;
    }

    @Scheduled(cron = "${simplebackup.snapshot.check-cron:0 0 4 * * SUN}")
    void checkAllTargets() {
        for (TargetView target : enabledResticTargets()) {
            try {
                var check = integrity.check(target.id(), properties.verifyPercent());
                log.info("Pruefung von {}: {}", target.name(), check.message());

                // Erst pruefen, dann zurueckholen: Ist das Repository kaputt, sagt die
                // Stichprobe nichts Neues.
                if (check.successful()) {
                    var sample = integrity.verifyByRestoringOneFile(target.id());
                    log.info("Stichprobe von {}: {}", target.name(), sample.message());
                }
            } catch (RuntimeException e) {
                // Ein kaputtes Ziel darf die Pruefung der uebrigen nicht verhindern.
                log.error("Pruefung von {} fehlgeschlagen", target.name(), e);
            }
        }
    }

    private java.util.List<TargetView> enabledResticTargets() {
        return catalog.listTargets().stream()
                .filter(TargetView::enabled)
                .filter(target -> target.mode() == TargetMode.RESTIC)
                .toList();
    }
}
