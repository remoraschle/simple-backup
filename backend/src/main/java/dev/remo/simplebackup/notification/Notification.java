package dev.remo.simplebackup.notification;

import java.util.Map;
import java.util.UUID;

/**
 * Eine Meldung, wie sie andere Module aufgeben.
 *
 * <p>Fertig formuliert: Dieses Modul kennt weder Plaene noch Laeufe und soll keinen Text
 * zusammenbauen muessen, der von ihnen handelt.
 *
 * @param eventType maschinenlesbare Art, etwa {@code RUN_FAILED}. Geht an Webhooks mit.
 * @param payload   zusaetzliche Felder fuer Webhooks; bei Pushover ungenutzt
 */
public record Notification(
        String eventType,
        Severity severity,
        String title,
        String body,
        UUID planId,
        UUID runId,
        Map<String, Object> payload) {

    public Notification {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Eine Meldung braucht eine Art");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Eine Meldung ohne Titel liest niemand");
        }
        severity = severity == null ? Severity.INFO : severity;
        body = body == null ? "" : body;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static Notification of(String eventType, Severity severity, String title, String body) {
        return new Notification(eventType, severity, title, body, null, null, Map.of());
    }
}
