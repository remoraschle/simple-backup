package dev.remo.simplebackup.snapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SnapshotRepository extends JpaRepository<Snapshot, UUID> {

    List<Snapshot> findAllByOrderBySnapshotTimeDesc();

    List<Snapshot> findAllByPlanIdOrderBySnapshotTimeDesc(UUID planId);

    List<Snapshot> findAllByTargetIdOrderBySnapshotTimeDesc(UUID targetId);

    Optional<Snapshot> findByTargetIdAndExternalId(UUID targetId, String externalId);

    boolean existsByTargetIdAndExternalId(UUID targetId, String externalId);
}
