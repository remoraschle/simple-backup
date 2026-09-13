package dev.remo.simplebackup.security;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Ein Anbieter, der nur eines kann: sich vorstellen.
 *
 * <p>Spring Boot baut die Registrierung aus dem Discovery-Dokument unter
 * {@code /.well-known/openid-configuration}. Ohne einen Anbieter, der antwortet, scheitert
 * schon der Start -- und damit liesse sich die Konfiguration, die im Betrieb verwendet wird,
 * ueberhaupt nicht pruefen.
 *
 * <p>Genau dafuer ist dieser Server da: Er beantwortet diese eine Anfrage und sonst nichts.
 * Was danach kommt -- Weiterleitung, Token, Benutzerangaben -- ist Sache von Spring Security
 * und wird hier nicht nachgebaut.
 */
final class FakeProvider {

    private static HttpServer server;

    /** Startet einmalig und laeuft, bis die JVM des Tests endet. */
    static synchronized String issuerUri() {
        if (server == null) {
            server = start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer start() {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String issuer = "http://127.0.0.1:" + created.getAddress().getPort();

            created.createContext("/.well-known/openid-configuration", exchange -> {
                // Der Aussteller muss mit der angefragten Adresse uebereinstimmen, sonst
                // lehnt Spring Security das Dokument ab -- zu Recht.
                byte[] body = """
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/authorize",
                          "token_endpoint": "%s/token",
                          "userinfo_endpoint": "%s/userinfo",
                          "jwks_uri": "%s/jwks",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }""".formatted(issuer, issuer, issuer, issuer, issuer)
                        .getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });

            created.start();
            return created;

        } catch (IOException e) {
            throw new IllegalStateException("Testanbieter liess sich nicht starten", e);
        }
    }

    private FakeProvider() {
    }
}
