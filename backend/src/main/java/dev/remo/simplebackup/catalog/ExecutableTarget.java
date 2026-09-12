package dev.remo.simplebackup.catalog;

import java.util.UUID;

/** Ein Ziel in der Form, in der die Ausfuehrung es braucht. */
public record ExecutableTarget(
        UUID targetId,
        String name,
        TargetMode mode,
        TargetConfig config,
        boolean enabled) {
}
