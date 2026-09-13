package dev.remo.simplebackup.run;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein echter HTTP-Server, der die GitHub-API nachstellt.
 *
 * <p>Bewusst ein echter Server: So laufen Blaettern, Kopffelder und JSON-Auswertung
 * tatsaechlich durch. Ein Mock wuerde bestaetigen, was man ihm beigebracht hat.
 */
final class FakeGitHub implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    FakeGitHub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Testserver liess sich nicht starten", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** @param pathWithQuery vollstaendiger Pfad inklusive Abfrage, wie der Client ihn schickt */
    FakeGitHub respond(String pathWithQuery, String json) {
        responses.put(pathWithQuery, json);
        return this;
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getQuery() == null ? ""
                        : "?" + exchange.getRequestURI().getQuery());

        requests.add(path);
        if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        }

        String body = responses.get(path);
        int status = body == null ? 404 : 200;
        byte[] payload = (body == null ? "{\"message\":\"Not Found\"}" : body)
                .getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);

        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
