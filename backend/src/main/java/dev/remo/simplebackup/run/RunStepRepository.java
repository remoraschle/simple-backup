package dev.remo.simplebackup.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RunStepRepository extends JpaRepository<RunStep, UUID> {

    /**
     * Schritte mit einem noch vermerkten Container.
     *
     * <p>Beim Start die Grundlage dafuer, laufende Runner wiederzufinden: Nur diese Schritte
     * koennen noch einen lebenden Container haben.
     */
    List<RunStep> findAllByContainerIdIsNotNull();
}
