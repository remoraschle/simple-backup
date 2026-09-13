package dev.remo.simplebackup.notification;

import dev.remo.simplebackup.secret.CredentialService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Versand ueber Pushover.
 *
 * <p>Kritische Meldungen gehen mit Prioritaet 2 hinaus. Pushover wiederholt sie dann so
 * lange, bis jemand quittiert -- und genau das ist der Grund, warum dieser Dienst
 * ausgewaehlt wurde: Ein fehlgeschlagenes Backup, das man im Schlaf weggewischt hat, ist
 * ein fehlgeschlagenes Backup, von dem niemand weiss.
 */
@Component
class PushoverTransport implements NotificationTransport {

    private static final URI ENDPOINT = URI.create("https://api.pushover.net/1/messages.json");

    /** Pushover kuerzt laengere Meldungen selbst; abgeschnitten sieht es aber wie ein Fehler aus. */
    private static final int MAX_BODY = 1000;

    private final HttpClient httpClient;
    private final CredentialService credentials;
    private final ObjectMapper objectMapper;
    private final URI endpoint;

    PushoverTransport(HttpClient httpClient, CredentialService credentials, ObjectMapper objectMapper,
            NotificationProperties properties) {
        this.httpClient = httpClient;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.endpoint = properties.pushoverEndpoint() == null
                ? ENDPOINT : URI.create(properties.pushoverEndpoint());
    }

    @Override
    public ChannelType type() {
        return ChannelType.PUSHOVER;
    }

    @Override
    public String send(ChannelConfig config, OutboxEntry entry) {
        ChannelConfig.Pushover pushover = (ChannelConfig.Pushover) config;
        PushoverCredentials access = readCredentials(pushover);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", access.apiToken());
        form.put("user", access.userKey());
        form.put("title", entry.getTitle());
        form.put("message", shorten(entry.getBody()));
        form.put("priority", String.valueOf(priorityFor(entry.getSeverity(), pushover.emergency())));

        if (pushover.device() != null && !pushover.device().isBlank()) {
            form.put("device", pushover.device());
        }
        if (form.get("priority").equals("2")) {
            form.put("retry", String.valueOf(pushover.retrySeconds()));
            form.put("expire", String.valueOf(pushover.expireSeconds()));
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncoded(form)))
                .build();

        HttpResponse<String> response = execute(request);

        if (response.statusCode() / 100 != 2) {
            // Der Text der Antwort nennt den Grund, etwa einen falschen Token. Er enthaelt
            // die gesendeten Werte nicht und kann deshalb gespeichert werden.
            throw new NotificationException("Pushover antwortete mit %d: %s"
                    .formatted(response.statusCode(), shorten(response.body())));
        }
        return receiptOf(response.body());
    }

    /**
     * @param emergency ob der Kanal Quittierungspflicht wuenscht
     * @return Prioritaet nach Pushover-Skala: 2 verlangt eine Quittung, 1 umgeht die
     *         Ruhezeiten, 0 ist die normale Zustellung
     */
    private static int priorityFor(Severity severity, boolean emergency) {
        return switch (severity) {
            case CRITICAL -> emergency ? 2 : 1;
            case WARNING -> 1;
            case INFO -> 0;
        };
    }

    private PushoverCredentials readCredentials(ChannelConfig.Pushover config) {
        try {
            return objectMapper.readValue(credentials.reveal(config.credentialId()),
                    PushoverCredentials.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new NotificationException(
                    "Der hinterlegte Pushover-Zugang ist unbrauchbar: " + e.getMessage());
        }
    }

    /** Die Quittung gibt es nur bei Prioritaet 2; sonst fehlt das Feld schlicht. */
    private String receiptOf(String body) {
        try {
            var node = objectMapper.readTree(body);
            return node.has("receipt") ? node.get("receipt").asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NotificationException("Pushover nicht erreichbar: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationException("Versand unterbrochen", e);
        }
    }

    private static String urlEncoded(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY - 1) + "…";
    }
}
