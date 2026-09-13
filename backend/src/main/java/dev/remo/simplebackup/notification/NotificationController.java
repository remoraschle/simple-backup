package dev.remo.simplebackup.notification;

import dev.remo.simplebackup.security.AuditService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService service;
    private final AuditService audit;

    NotificationController(NotificationService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/channels")
    List<NotificationViews.ChannelView> listChannels() {
        return service.listChannels();
    }

    @PostMapping("/channels")
    ResponseEntity<NotificationViews.ChannelView> createChannel(
            @Valid @RequestBody NotificationRequests.SaveChannel request, Principal principal) {

        var created = service.createChannel(request);
        audit.record(principal.getName(), "CHANNEL_CREATED", "notification_channel",
                created.id().toString(), null);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/channels/{id}")
    NotificationViews.ChannelView updateChannel(@PathVariable UUID id,
            @Valid @RequestBody NotificationRequests.SaveChannel request, Principal principal) {

        audit.record(principal.getName(), "CHANNEL_UPDATED", "notification_channel", id.toString(), null);
        return service.updateChannel(id, request);
    }

    @DeleteMapping("/channels/{id}")
    ResponseEntity<Void> deleteChannel(@PathVariable UUID id, Principal principal) {
        service.deleteChannel(id);
        audit.record(principal.getName(), "CHANNEL_DELETED", "notification_channel", id.toString(), null);
        return ResponseEntity.noContent().build();
    }

    /** Legt eine Probemeldung in den Postausgang; zugestellt wird sie wie jede andere. */
    @PostMapping("/channels/{id}/test")
    ResponseEntity<Map<String, String>> testChannel(@PathVariable UUID id, Principal principal) {
        UUID entryId = service.sendTest(id);
        audit.record(principal.getName(), "CHANNEL_TESTED", "notification_channel", id.toString(), null);

        return ResponseEntity.accepted().body(Map.of("outboxId", entryId.toString()));
    }

    @GetMapping("/outbox")
    Page<NotificationViews.OutboxView> outbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return service.listOutbox(PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
