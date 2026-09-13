package dev.remo.simplebackup.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein echter HTTP-Server als Gegenstelle.
 *
 * <p>Bewusst ein echter Server: Nur so laufen Formularkodierung, JSON-Erzeugung, Kopffelder
 * und Zeitlimits tatsaechlich durch. Ein Mock wuerde bestaetigen, was man ihm beigebracht
 * hat -- und genau die Stellen auslassen, an denen ein HTTP-Client bricht.
 */
final class FakeEndpoint implements AutoCloseable {

    record Recorded(String method, Map<String, List<String>> headers, String body) {
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private volatile int status = 200;
    private volatile String responseBody = "{\"status\":1}";

    FakeEndpoint() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Testserver liess sich nicht starten", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/melden";
    }

    void respondWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    Recorded lastRequest() {
        if (requests.isEmpty()) {
            throw new AssertionError("Es ist keine Anfrage angekommen");
        }
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new Recorded(exchange.getRequestMethod(),
                Map.copyOf(exchange.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));

        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);

        try (var out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Zerlegt einen Formularrumpf, damit einzelne Felder geprueft werden koennen. */
    static Map<String, String> formFields(String body) {
        var fields = new java.util.LinkedHashMap<String, String>();
        for (String pair : body.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                fields.put(java.net.URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }
}
