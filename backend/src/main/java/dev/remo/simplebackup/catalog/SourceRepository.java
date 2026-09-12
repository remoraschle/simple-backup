package dev.remo.simplebackup.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceRepository extends JpaRepository<BackupSource, UUID> {

    boolean existsByName(String name);

    Optional<BackupSource> findByName(String name);
}
