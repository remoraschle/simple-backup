package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.CatalogService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sucht faellige Plaene und stoesst ihre Laeufe an.
 *
 * <p>Ein Datenbank-Poller statt eines Zeitplaner-Rahmenwerks: Die Abfrage sperrt faellige
 * Plaene und ueberspringt bereits gesperrte, womit mehrere Instanzen gleichzeitig arbeiten
 * koennen, ohne denselben Plan doppelt zu starten. Das kostet eine Abfrage alle dreissig
 * Sekunden und spart eine Abhaengigkeit samt elf zusaetzlicher Tabellen.
 */
@Component
class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    /** Wie viele Plaene je Durchgang uebernommen werden. */
    private static final int BATCH_SIZE = 10;

    private final CatalogService catalog;
    private final RunService runService;
    private final StartupRecovery recovery;

    BackupScheduler(CatalogService catalog, RunService runService, StartupRecovery recovery) {
        this.catalog = catalog;
        this.runService = runService;
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${simplebackup.run.scheduler-poll-interval:PT30S}")
    void pollDuePlans() {
        List<UUID> due;
        try {
            due = catalog.claimDuePlans(BATCH_SIZE);
        } catch (RuntimeException e) {
            // Ein Fehler darf den Zeitplaner nicht dauerhaft anhalten -- sonst laeuft ab
            // diesem Moment gar kein Backup mehr.
            log.error("Suche nach faelligen Plaenen fehlgeschlagen", e);
            return;
        }

        for (UUID planId : due) {
            try {
                runService.startRun(planId, RunTrigger.SCHEDULE)
                        .ifPresent(runId -> log.info("Plan {} gestartet als Lauf {}", planId, runId));
            } catch (RuntimeException e) {
                log.error("Plan {} liess sich nicht starten", planId, e);
            }
        }
    }

    /**
     * Bringt nach einem Neustart Ordnung in die Zeitplanung.
     *
     * <p>War der Server laenger aus, liegen gespeicherte Termine in der Vergangenheit. Ohne
     * diese Korrektur wuerden sie alle beim ersten Durchgang gleichzeitig faellig.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        recovery.recoverInterruptedRuns();

        int adjusted = catalog.rescheduleAfterDowntime();
        if (adjusted > 0) {
            log.info("{} Plaene nach Stillstand neu eingeplant", adjusted);
        }
    }
}
