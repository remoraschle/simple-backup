package dev.remo.simplebackup.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface RunRepository extends JpaRepository<BackupRun, UUID> {

    Page<BackupRun> findAllByOrderByQueuedAtDesc(Pageable pageable);

    Page<BackupRun> findAllByPlanIdOrderByQueuedAtDesc(UUID planId, Pageable pageable);

    /**
     * Laeufe, die beim Start noch als aktiv gelten.
     *
     * <p>Nach einem Neustart sind das entweder Laeufe, deren Container weiterlaufen -- an die
     * haengt sich das Backend wieder an -- oder verwaiste Reste eines Absturzes.
     */
    List<BackupRun> findAllByStatusIn(List<RunStatus> statuses);

    boolean existsByPlanIdAndStatusIn(UUID planId, List<RunStatus> statuses);

    long countByStatusIn(List<RunStatus> statuses);

    /**
     * Der letzte Lauf eines Plans mit einem dieser Zustaende.
     *
     * <p>Fuer den Totmannschalter: Was zaehlt, ist nicht wann zuletzt gestartet wurde,
     * sondern wann zuletzt tatsaechlich Daten ankamen.
     */
    Optional<BackupRun> findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(
            UUID planId, List<RunStatus> statuses);

    /** Der letzte abgeschlossene Lauf, gleich mit welchem Ausgang -- fuer die Kennzahlen. */
    Optional<BackupRun> findFirstByPlanIdAndFinishedAtNotNullOrderByFinishedAtDesc(UUID planId);
}
