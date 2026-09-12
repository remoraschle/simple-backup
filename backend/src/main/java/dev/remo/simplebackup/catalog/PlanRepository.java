package dev.remo.simplebackup.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PlanRepository extends JpaRepository<BackupPlan, UUID> {

    /** Nur Kennung und Name -- damit Listen einen Plan benennen koennen, ohne ihn zu laden. */
    interface PlanName {
        UUID getId();

        String getName();
    }

    List<PlanName> findByIdIn(Collection<UUID> ids);

    boolean existsByName(String name);

    List<BackupPlan> findAllByOrderByNameAsc();

    /** Ob eine Quelle noch verwendet wird -- sonst liesse sie sich unter einem Plan wegloeschen. */
    boolean existsBySourceId(UUID sourceId);

    @Query("select count(p) > 0 from BackupPlan p join p.targets t where t.id = :targetId")
    boolean existsByTargetId(@Param("targetId") UUID targetId);

    @Query("select count(p) > 0 from BackupPlan p where p.retentionPolicy.id = :policyId")
    boolean existsByRetentionPolicyId(@Param("policyId") UUID policyId);

    /**
     * Faellige Plaene, gesperrt und fuer andere Instanzen uebersprungen.
     *
     * <p>{@code SKIP LOCKED} ist der Kern der Nebenlaeufigkeit: Mehrere Instanzen koennen
     * gleichzeitig suchen, ohne sich zu blockieren, und keine zwei uebernehmen denselben
     * Plan. Ohne den Zusatz warteten sie aufeinander, statt sich die Arbeit zu teilen.
     */
    @Query(value = """
            SELECT * FROM backup_plan
            WHERE enabled AND next_run_at IS NOT NULL AND next_run_at <= :now
            ORDER BY next_run_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BackupPlan> findDuePlansForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
