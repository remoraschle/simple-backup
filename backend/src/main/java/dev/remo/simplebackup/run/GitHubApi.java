package dev.remo.simplebackup.run;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Der schmale Ausschnitt der GitHub-API, den eine Sicherung braucht.
 *
 * <p>Vom Backend aus und nicht aus dem Runner: Die Abfrage braucht den Token im Klartext, und
 * der liegt ohnehin nur hier. Ein Runner bekaeme ihn nur, um dieselbe Liste zu holen.
 *
 * <p>Geblaettert wird vollstaendig. Wer dreissig Repositories hat und nur die ersten dreissig
 * sichert, merkt beim einunddreissigsten nichts -- bis er es braucht.
 */
@Component
class GitHubApi {

    /** GitHub liefert hoechstens hundert Eintraege je Seite. */
    private static final int PAGE_SIZE = 100;

    /** Sicherheitsnetz gegen eine Endlosschleife, falls die API sich unerwartet verhaelt. */
    private static final int MAX_PAGES = 50;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    GitHubApi(HttpClient httpClient, ObjectMapper objectMapper, RunProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.githubApiUrl();
    }

    /**
     * Die Repositories eines Benutzers oder einer Organisation.
     *
     * <p>Erst als Organisation versucht, dann als Benutzer: Von aussen sieht man einem Namen
     * nicht an, was er ist, und die API beantwortet die falsche Frage mit 404.
     */
    List<Repository> listRepositories(String owner, String token, boolean includeForks) {
        List<Repository> repositories = new ArrayList<>();

        String path = exists("/orgs/" + owner, token) ? "/orgs/" + owner + "/repos"
                : "/users/" + owner + "/repos";

        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = get(path + "?per_page=" + PAGE_SIZE + "&page=" + page, token);

            if (!body.isArray() || body.isEmpty()) {
                break;
            }
            for (JsonNode node : body) {
                boolean fork = node.has("fork") && node.get("fork").asBoolean();
                if (fork && !includeForks) {
                    continue;
                }
                repositories.add(new Repository(
                        node.get("name").asString(),
                        node.get("full_name").asString(),
                        node.has("clone_url") ? node.get("clone_url").asString() : null,
                        fork,
                        node.has("archived") && node.get("archived").asBoolean()));
            }
            if (body.size() < PAGE_SIZE) {
                break;
            }
        }
        return repositories;
    }

    /**
     * Issues und Releases eines Repositories als JSON.
     *
     * <p>Sie liegen nicht im Git-Repository und waeren nach einem Verlust des Kontos weg --
     * der Code liesse sich aus jedem Klon wiederherstellen, die Diskussion darueber nicht.
     */
    String metadata(String fullName, String token) {
        var metadata = objectMapper.createObjectNode();
        metadata.put("repository", fullName);
        metadata.put("fetchedAt", java.time.Instant.now().toString());
        metadata.set("issues", get("/repos/" + fullName + "/issues?state=all&per_page=" + PAGE_SIZE, token));
        metadata.set("releases", get("/repos/" + fullName + "/releases?per_page=" + PAGE_SIZE, token));

        return objectMapper.writeValueAsString(metadata);
    }

    private boolean exists(String path, String token) {
        try {
            return send(path, token).statusCode() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private JsonNode get(String path, String token) {
        HttpResponse<String> response = send(path, token);

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub antwortete mit %d auf %s"
                    .formatted(response.statusCode(), path));
        }
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("GitHub ist nicht erreichbar: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abfrage unterbrochen", e);
        }
    }

    /** @param archived archivierte Repositories aendern sich nicht mehr, gesichert werden sie trotzdem */
    record Repository(String name, String fullName, String cloneUrl, boolean fork, boolean archived) {
    }
}
