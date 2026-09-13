package dev.remo.simplebackup.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein Mailserver, der gerade genug spricht.
 *
 * <p>Bewusst ein echter Server auf einem echten Socket: Nur so laufen Verbindungsaufbau,
 * der SMTP-Dialog, die Kodierung der Kopfzeilen und das Zeilenende {@code CRLF}
 * tatsaechlich durch. Ein Mock wuerde bestaetigen, was man ihm beigebracht hat.
 *
 * <p>Er kennt nur den geraden Weg -- kein STARTTLS, keine Anmeldung, keine Fehlerfaelle
 * ausser dem einen, den er auf Wunsch vorspielt.
 */
final class FakeMailServer implements AutoCloseable {

    /** Eine angenommene Nachricht, roh wie sie ueber die Leitung kam. */
    record Message(String from, List<String> recipients, String data) {

        /** @return der Wert einer Kopfzeile, oder {@code null} */
        String header(String name) {
            for (String line : data.split("\n")) {
                if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                    return line.substring(name.length() + 1).trim();
                }
            }
            return null;
        }

        /** Der Text hinter der Leerzeile, die Kopf und Rumpf trennt. */
        String body() {
            int separator = data.indexOf("\n\n");
            return separator < 0 ? "" : data.substring(separator + 2).trim();
        }
    }

    private final ServerSocket socket;
    private final Thread acceptor;
    private final List<Message> messages = new CopyOnWriteArrayList<>();

    private volatile boolean rejecting;

    FakeMailServer() {
        try {
            socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("Testmailserver liess sich nicht starten", e);
        }
        acceptor = Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return socket.getLocalPort();
    }

    String host() {
        return "127.0.0.1";
    }

    /** Laesst den Server jede Nachricht ablehnen -- der Fall, der im Postausgang landen soll. */
    void rejectEverything() {
        this.rejecting = true;
    }

    List<Message> messages() {
        return List.copyOf(messages);
    }

    Message lastMessage() {
        if (messages.isEmpty()) {
            throw new AssertionError("Es ist keine Nachricht angekommen");
        }
        return messages.get(messages.size() - 1);
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                Thread.ofVirtual().start(() -> handle(connection));
            } catch (IOException e) {
                return; // geschlossen
            }
        }
    }

    private void handle(Socket connection) {
        try (connection;
                var in = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                        StandardCharsets.UTF_8));
                var out = new PrintWriter(connection.getOutputStream(), true, StandardCharsets.UTF_8)) {

            out.print("220 testserver bereit\r\n");
            out.flush();

            String from = null;
            var recipients = new ArrayList<String>();
            String line;

            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase(java.util.Locale.ROOT);

                if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                    // Mehrzeilige Antwort: erst mit Bindestrich, die letzte mit Leerzeichen.
                    out.print("250-testserver\r\n250 OK\r\n");

                } else if (command.startsWith("MAIL FROM")) {
                    from = address(line);
                    out.print("250 OK\r\n");

                } else if (command.startsWith("RCPT TO")) {
                    recipients.add(address(line));
                    out.print(rejecting ? "550 Empfaenger unbekannt\r\n" : "250 OK\r\n");

                } else if (command.startsWith("DATA")) {
                    out.print("354 Weiter\r\n");
                    out.flush();
                    messages.add(new Message(from, List.copyOf(recipients), readData(in)));
                    out.print("250 OK\r\n");

                } else if (command.startsWith("QUIT")) {
                    out.print("221 Tschuess\r\n");
                    out.flush();
                    return;

                } else {
                    out.print("250 OK\r\n");
                }
                out.flush();
            }
        } catch (IOException e) {
            // Ein abgebrochener Dialog ist fuer diesen Testserver kein Ereignis.
        }
    }

    /** Liest bis zur Zeile mit einem einzelnen Punkt -- so endet eine Nachricht in SMTP. */
    private static String readData(BufferedReader in) throws IOException {
        var data = new StringBuilder();
        String line;

        while ((line = in.readLine()) != null && !line.equals(".")) {
            data.append(line).append('\n');
        }
        return data.toString();
    }

    private static String address(String line) {
        int start = line.indexOf('<');
        int end = line.indexOf('>');
        return start < 0 || end < 0 ? line : line.substring(start + 1, end);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Beim Aufraeumen nicht mehr von Interesse.
        }
        acceptor.interrupt();
    }
}
