package dev.remo.simplebackup.notification;

import dev.remo.simplebackup.secret.CredentialService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Versand an einen beliebigen Endpunkt, der JSON entgegennimmt.
 *
 * <p>Damit laesst sich alles anbinden, was eine Adresse hat: Gotify, ntfy, Discord, ein
 * eigenes Skript. Das Format ist bewusst schlicht und stabil -- wer es umformen muss, hat
 * am anderen Ende ohnehin ein Skript.
 */
@Component
class WebhookTransport implements NotificationTransport {

    private final HttpClient httpClient;
    private final CredentialService credentials;
    private final ObjectMapper objectMapper;

    WebhookTransport(HttpClient httpClient, CredentialService credentials, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public String send(ChannelConfig config, OutboxEntry entry) {
        ChannelConfig.Webhook webhook = (ChannelConfig.Webhook) config;

        var builder = HttpRequest.newBuilder(URI.create(webhook.url()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyOf(entry)));

        if (webhook.headerName() != null && webhook.credentialId() != null) {
            builder.header(webhook.headerName(), credentials.reveal(webhook.credentialId()));
        }

        HttpResponse<String> response = execute(builder.build());

        if (response.statusCode() / 100 != 2) {
            throw new NotificationException("Der Endpunkt antwortete mit %d: %s"
                    .formatted(response.statusCode(), shorten(response.body())));
        }
        return null;
    }

    /**
     * Der Rumpf der Anfrage.
     *
     * <p>Die Nutzlast der Meldung steht unter {@code data} und nicht auf oberster Ebene:
     * Sonst koennte ein zusaetzliches Feld eines Tages ein festes ueberschreiben.
     */
    private String bodyOf(OutboxEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", entry.getEventType());
        body.put("severity", entry.getSeverity().name());
        body.put("title", entry.getTitle());
        body.put("message", entry.getBody());
        body.put("planId", entry.getPlanId());
        body.put("runId", entry.getRunId());
        body.put("occurredAt", entry.getCreatedAt().toString());
        body.put("data", entry.getPayload() == null ? Map.of() : objectMapper.readTree(entry.getPayload()));

        return objectMapper.writeValueAsString(body);
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NotificationException("Der Endpunkt ist nicht erreichbar: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationException("Versand unterbrochen", e);
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }
}
