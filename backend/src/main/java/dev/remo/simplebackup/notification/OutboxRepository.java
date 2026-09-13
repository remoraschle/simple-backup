package dev.remo.simplebackup.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Faellige Eintraege, gesperrt und fuer andere Instanzen uebersprungen.
     *
     * <p>Wie beim Zeitplaner: Mehrere Instanzen duerfen gleichzeitig zustellen, aber keine
     * Meldung zweimal. Ohne {@code SKIP LOCKED} bekaeme man beides -- Warteschlangen und
     * doppelte Alarme.
     */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntry> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    Page<OutboxEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByEventTypeAndPlanIdAndCreatedAtAfter(String eventType, UUID planId, Instant since);
}
