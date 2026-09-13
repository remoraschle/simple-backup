package dev.remo.simplebackup.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    List<NotificationChannel> findAllByOrderByNameAsc();

    boolean existsByName(String name);
}
