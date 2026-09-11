package dev.remo.simplebackup.engine.docker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ein echter HTTP-Server, der die Docker-API nachstellt.
 *
 * <p>Bewusst ein echter Server statt eines Mock-Frameworks: So laufen JSON-Serialisierung,
 * URL-Kodierung, TAR-Erzeugung und Fehlerbehandlung tatsaechlich durch -- genau die Stellen,
 * an denen ein Client bricht. Ein Mock wuerde nur bestaetigen, was man ihm beigebracht hat.
 */
final class FakeDockerApi implements AutoCloseable {

    /** Aufgezeichnete Anfrage. */
    record Recorded(String method, String path, String query, byte[] body) {

        String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new ArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();

    private record Response(int status, byte[] body, String contentType) {
    }

    FakeDockerApi() {
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

    /** Legt die Antwort fuer einen Pfad fest. Der Pfad ist ohne API-Version anzugeben. */
    FakeDockerApi respond(String path, int status, String body) {
        responses.put(path, new Response(status, body.getBytes(StandardCharsets.UTF_8), "application/json"));
        return this;
    }

    FakeDockerApi respondRaw(String path, int status, byte[] body) {
        responses.put(path, new Response(status, body, "application/octet-stream"));
        return this;
    }

    synchronized List<Recorded> requests() {
        return List.copyOf(requests);
    }

    synchronized Recorded lastRequestTo(String pathFragment) {
        return requests.reversed().stream()
                .filter(request -> request.path().contains(pathFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Keine Anfrage an '%s'. Aufgezeichnet: %s".formatted(pathFragment,
                                requests.stream().map(Recorded::path).toList())));
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }

        String fullPath = exchange.getRequestURI().getPath();
        // Die API-Version aus dem Pfad entfernen, damit Tests sie nicht angeben muessen.
        String path = fullPath.replaceFirst("^/v[0-9.]+", "");

        synchronized (this) {
            requests.add(new Recorded(exchange.getRequestMethod(), path,
                    exchange.getRequestURI().getQuery(), body));
        }

        Response response = responses.getOrDefault(path, new Response(200, "{}".getBytes(StandardCharsets.UTF_8),
                "application/json"));

        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (var out = exchange.getResponseBody()) {
            out.write(response.body());
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
