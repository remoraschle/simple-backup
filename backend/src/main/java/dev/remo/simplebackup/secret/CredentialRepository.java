package dev.remo.simplebackup.secret;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByName(String name);

    boolean existsByName(String name);
}
