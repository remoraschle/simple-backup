package dev.remo.simplebackup.engine.restic;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Liest die Meldungen aus der JSON-Ausgabe von restic.
 *
 * <p>Bewusst nachsichtig: Zeilen, die kein JSON sind, unbekannte Meldungsarten und fehlende
 * Felder fuehren nicht zu einem Fehler, sondern werden uebergangen. restic mischt je nach
 * Version Warnungen im Klartext unter die JSON-Zeilen und ergaenzt Meldungsarten -- ein
 * Backup daran scheitern zu lassen, waere die falsche Abwaegung.
 *
 * <p>Der Rueckgabewert des Prozesses bleibt die verbindliche Auskunft ueber Erfolg oder
 * Misserfolg; diese Auswertung dient der Anzeige und dem Protokoll.
 */
public class ResticOutputParser {

    private final ObjectMapper objectMapper;

    public ResticOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return die erkannte Meldung, oder leer fuer alles, was nicht ausgewertet wird */
    public Optional<ResticMessage> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String trimmed = line.strip();
        if (!trimmed.startsWith("{")) {
            return Optional.empty();
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(trimmed);
        } catch (RuntimeException e) {
            // Abgeschnittene oder ineinandergeratene Zeilen kommen vor; sie sind kein
            // Grund, den Lauf als gescheitert zu behandeln.
            return Optional.empty();
        }

        return switch (text(node, "message_type")) {
            case "status" -> Optional.of(toProgress(node));
            case "summary" -> Optional.of(toSummary(node));
            case "error" -> Optional.of(toFailure(node));
            case null, default -> Optional.empty();
        };
    }

    private static ResticMessage.Progress toProgress(JsonNode node) {
        return new ResticMessage.Progress(
                node.path("percent_done").asDouble(0.0),
                optionalLong(node, "files_done"),
                optionalLong(node, "total_files"),
                optionalLong(node, "bytes_done"),
                optionalLong(node, "total_bytes"),
                optionalLong(node, "seconds_remaining"));
    }

    private static ResticMessage.Summary toSummary(JsonNode node) {
        return new ResticMessage.Summary(
                optionalLong(node, "files_new"),
                optionalLong(node, "files_changed"),
                optionalLong(node, "files_unmodified"),
                optionalLong(node, "total_files_processed"),
                optionalLong(node, "total_bytes_processed"),
                optionalLong(node, "data_added"),
                node.has("total_duration") ? node.path("total_duration").asDouble() : null,
                text(node, "snapshot_id"));
    }

    private static ResticMessage.Failure toFailure(JsonNode node) {
        // Die Fehlermeldung liegt je nach Version unmittelbar oder in einem Unterobjekt.
        String message = node.path("error").isObject()
                ? text(node.path("error"), "message")
                : text(node, "error");

        return new ResticMessage.Failure(
                message == null ? "Unbekannter Fehler" : message,
                text(node, "during"),
                text(node, "item"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
