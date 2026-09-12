package dev.remo.simplebackup.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TargetRepository extends JpaRepository<BackupTarget, UUID> {

    boolean existsByName(String name);

    List<BackupTarget> findAllByEnabledTrue();
}
