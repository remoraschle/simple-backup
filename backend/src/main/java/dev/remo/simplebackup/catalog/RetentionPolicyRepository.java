package dev.remo.simplebackup.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, UUID> {

    boolean existsByName(String name);
}
