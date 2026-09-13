package dev.remo.simplebackup.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Was die API entgegennimmt. */
public final class NotificationRequests {

    private NotificationRequests() {
    }

    public record SaveChannel(
            @NotBlank String name,
            @NotNull @Valid ChannelConfig config,
            boolean enabled,
            Severity minSeverity) {

        public SaveChannel {
            minSeverity = minSeverity == null ? Severity.WARNING : minSeverity;
        }
    }
}
