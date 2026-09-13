package dev.remo.simplebackup.restic;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Liest die Ausgabe von {@code restic snapshots --json} und {@code restic ls --json}.
 *
 * <p>Zeilenweise und fehlertolerant: restic mischt in dieselbe Ausgabe Meldungen, die hier
 * nicht interessieren, und ergaenzt mit jeder Version Felder. Eine Zeile, die sich nicht
 * deuten laesst, wird uebergangen statt zum Fehler erklaert -- sonst scheitert die
 * Wiederherstellung an einer Nebensaechlichkeit.
 */
@Component
public class ResticListingParser {

    private final ObjectMapper objectMapper;

    public ResticListingParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Die Snapshots aus einer JSON-Liste ({@code restic snapshots --json}). */
    public List<ResticListing.Snapshot> parseSnapshots(String output) {
        List<ResticListing.Snapshot> snapshots = new ArrayList<>();

        for (JsonNode node : readArrayOrLines(output)) {
            String id = text(node, "id");
            if (id == null) {
                continue;
            }
            snapshots.add(new ResticListing.Snapshot(id, instant(node, "time"),
                    text(node, "hostname"), strings(node, "tags"), strings(node, "paths")));
        }
        return snapshots;
    }

    /**
     * Die Eintraege aus {@code restic ls --json}.
     *
     * <p>Die erste Zeile beschreibt den Snapshot selbst und traegt kein {@code struct_type}
     * {@code node}; sie wird uebergangen.
     */
    public List<ResticListing.Node> parseNodes(String output) {
        List<ResticListing.Node> nodes = new ArrayList<>();

        for (JsonNode node : readArrayOrLines(output)) {
            String path = text(node, "path");
            String type = text(node, "type");

            if (path == null || type == null) {
                continue;
            }
            nodes.add(new ResticListing.Node(path, text(node, "name"), "dir".equals(type),
                    node.has("size") ? node.get("size").asLong() : null, instant(node, "mtime")));
        }
        return nodes;
    }

    /**
     * restic gibt je nach Kommando entweder ein JSON-Array oder eine Zeile je Datensatz aus.
     * Beides kommt hier an, also wird beides gelesen.
     */
    private List<JsonNode> readArrayOrLines(String output) {
        List<JsonNode> nodes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return nodes;
        }

        String trimmed = output.strip();
        if (trimmed.startsWith("[")) {
            try {
                objectMapper.readTree(trimmed).forEach(nodes::add);
                return nodes;
            } catch (JacksonException e) {
                // Weiter mit der zeilenweisen Auswertung: Besser ein Teil der Liste als nichts.
            }
        }

        for (String line : trimmed.split("\\R")) {
            String candidate = line.strip();
            if (candidate.startsWith("{")) {
                try {
                    nodes.add(objectMapper.readTree(candidate));
                } catch (JacksonException e) {
                    // uebergehen
                }
            }
        }
        return nodes;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asString() : null;
    }

    private static List<String> strings(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.get(field).forEach(entry -> values.add(entry.asString()));
        return values;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
