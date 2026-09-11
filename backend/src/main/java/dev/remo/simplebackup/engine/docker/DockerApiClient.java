package dev.remo.simplebackup.engine.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Schmaler Zugang zur Docker-Engine-API.
 *
 * <p>Spricht ausschliesslich HTTP -- im Betrieb mit dem Socket-Proxy, nie unmittelbar mit
 * {@code /var/run/docker.sock}. Der Code kann den Socket damit gar nicht erreichen, selbst
 * wenn jemand es versuchte.
 *
 * <p>Verwendet den {@link HttpClient} des JDK statt eines hoeheren Clients, weil zwei
 * Anforderungen sonst im Weg staenden: Der Logstrom muss offen bleiben, waehrend er gelesen
 * wird, und das Warten auf das Ende eines Laufs braucht ein eigenes, sehr langes Zeitlimit.
 */
public class DockerApiClient {

    private static final Logger log = LoggerFactory.getLogger(DockerApiClient.class);

    private final DockerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DockerApiClient(DockerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /** @return Kennung des erzeugten Containers */
    public String createContainer(DockerDto.CreateContainer request, String name) {
        String body = writeJson(request);
        var response = send(requestBuilder("/containers/create?name=" + encode(name))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .timeout(properties.requestTimeout()));

        var created = readJson(response.body(), DockerDto.CreatedContainer.class);
        if (created.warnings() != null && !created.warnings().isEmpty()) {
            log.warn("Docker meldet beim Anlegen von {}: {}", name, created.warnings());
        }
        return created.id();
    }

    public void startContainer(String containerId) {
        send(requestBuilder("/containers/" + containerId + "/start")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(properties.requestTimeout()));
    }

    /**
     * Wartet, bis der Container endet.
     *
     * @return Rueckgabewert des Prozesses im Container
     * @throws DockerApiException bei Ablauf des Zeitlimits -- der Aufrufer beendet den
     *                            Container dann selbst
     */
    public int waitForExit(String containerId, Duration timeout) {
        var response = send(requestBuilder("/containers/" + containerId + "/wait")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(timeout));

        return readJson(response.body(), DockerDto.WaitResult.class).statusCode();
    }

    /** @param signal etwa {@code SIGTERM} oder {@code SIGKILL} */
    public void kill(String containerId, String signal) {
        try {
            send(requestBuilder("/containers/" + containerId + "/kill?signal=" + encode(signal))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(properties.requestTimeout()));
        } catch (DockerApiException e) {
            // 409 bedeutet: laeuft nicht mehr. Genau das war das Ziel.
            if (e.statusCode() != 409 && !e.isNotFound()) {
                throw e;
            }
        }
    }

    public void remove(String containerId, boolean force) {
        try {
            send(requestBuilder("/containers/" + containerId + "?force=" + force + "&v=true")
                    .DELETE()
                    .timeout(properties.requestTimeout()));
        } catch (DockerApiException e) {
            if (!e.isNotFound()) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ Beobachtung

    /**
     * Oeffnet den Logstrom.
     *
     * <p>Bewusst ohne Zeitlimit: Der Strom bleibt fuer die Dauer des Laufs offen. Der Inhalt
     * ist gerahmt und gehoert durch {@link DockerLogStreamDecoder}.
     *
     * <p>Der Aufrufer schliesst den Strom.
     */
    public InputStream openLogStream(String containerId, boolean follow) {
        String query = "?stdout=true&stderr=true&timestamps=false&follow=" + follow;
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    requestBuilder("/containers/" + containerId + "/logs" + query).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                response.body().close();
                throw new DockerApiException(
                        "Logstrom von %s nicht verfuegbar".formatted(containerId), response.statusCode());
            }
            return response.body();

        } catch (IOException e) {
            throw new DockerApiException("Logstrom von %s nicht lesbar".formatted(containerId), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerApiException("Warten auf den Logstrom unterbrochen", e);
        }
    }

    /** @return leer, wenn der Container nicht mehr existiert */
    public Optional<DockerDto.ContainerDetails> inspect(String containerId) {
        try {
            var response = send(requestBuilder("/containers/" + containerId + "/json")
                    .GET().timeout(properties.requestTimeout()));
            return Optional.of(readJson(response.body(), DockerDto.ContainerDetails.class));
        } catch (DockerApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Sucht Container anhand eines Labels, auch beendete.
     *
     * <p>Grundlage fuer das Wiederanhaengen nach einem Neustart und fuer das Aufraeumen
     * verwaister Container.
     */
    public List<DockerDto.ContainerSummary> listByLabel(String label, String value) {
        String filters = """
                {"label":["%s=%s"]}""".formatted(label, value);
        var response = send(requestBuilder("/containers/json?all=true&filters=" + encode(filters))
                .GET().timeout(properties.requestTimeout()));

        return List.of(readJson(response.body(), DockerDto.ContainerSummary[].class));
    }

    // ------------------------------------------------------------------ Dateien

    /**
     * Legt Dateien im Container ab, bevor er startet.
     *
     * <p>So gelangen Geheimnisse in den Runner: nicht als Umgebungsvariable -- die waere
     * dauerhaft ueber {@code docker inspect} lesbar -- und nicht als Argument, das in
     * {@code ps} fuer jeden sichtbar waere.
     *
     * @param directory Zielverzeichnis im Container
     * @param files     Dateiname auf Inhalt
     * @param mode      Zugriffsrechte, etwa {@code 0600}
     */
    public void copyFilesInto(String containerId, String directory, Map<String, String> files, int mode) {
        byte[] archive = buildTar(files, mode);

        send(requestBuilder("/containers/" + containerId + "/archive?path=" + encode(directory))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(archive))
                .header("Content-Type", "application/x-tar")
                .timeout(properties.requestTimeout()));
    }

    private static byte[] buildTar(Map<String, String> files, int mode) {
        var buffer = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);

                var entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                entry.setMode(mode);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new DockerApiException("Archiv liess sich nicht erzeugen", e);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ System

    public DockerDto.SystemInfo systemInfo() {
        var response = send(requestBuilder("/info").GET().timeout(properties.requestTimeout()));
        return readJson(response.body(), DockerDto.SystemInfo.class);
    }

    public boolean imageExists(String reference) {
        String filters = """
                {"reference":["%s"]}""".formatted(reference);
        var response = send(requestBuilder("/images/json?filters=" + encode(filters))
                .GET().timeout(properties.requestTimeout()));

        return readJson(response.body(), DockerDto.ImageSummary[].class).length > 0;
    }

    /** Zieht ein Image. Kann lange dauern, deshalb ein eigenes Zeitlimit. */
    public void pullImage(String reference, Duration timeout) {
        String[] parts = splitReference(reference);
        var response = send(requestBuilder(
                "/images/create?fromImage=" + encode(parts[0]) + "&tag=" + encode(parts[1]))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(timeout));

        // Der Fortschritt kommt als Strom von JSON-Objekten; ein "error" darin bedeutet
        // Fehlschlag trotz HTTP 200.
        if (response.body().contains("\"error\"")) {
            throw new DockerApiException("Image %s liess sich nicht laden".formatted(reference), 200);
        }
    }

    private static String[] splitReference(String reference) {
        int separator = reference.lastIndexOf(':');
        int lastSlash = reference.lastIndexOf('/');
        // Ein Doppelpunkt vor dem letzten Schraegstrich gehoert zum Port der Registry.
        if (separator > lastSlash) {
            return new String[] {reference.substring(0, separator), reference.substring(separator + 1)};
        }
        return new String[] {reference, "latest"};
    }

    // ------------------------------------------------------------------ Hilfsmittel

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(properties.baseUrl() + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new DockerApiException(describeFailure(request, response), response.statusCode());
            }
            return response;

        } catch (HttpTimeoutException e) {
            throw new DockerApiException(
                    "Zeitlimit bei %s %s".formatted(request.method(), request.uri().getPath()), e);
        } catch (IOException e) {
            throw new DockerApiException("""
                    Docker-API unter %s nicht erreichbar: %s

                    Laeuft der Socket-Proxy, und zeigt DOCKER_HOST auf ihn?"""
                    .formatted(properties.host(), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerApiException("Anfrage an die Docker-API unterbrochen", e);
        }
    }

    /**
     * Ein 403 kommt im Betrieb fast immer vom Socket-Proxy, nicht von Docker. Die Meldung
     * soll das sagen, statt den Betreiber einen nackten Statuscode deuten zu lassen.
     */
    private String describeFailure(HttpRequest request, HttpResponse<String> response) {
        String base = "Docker-API antwortete auf %s %s mit %d: %s"
                .formatted(request.method(), request.uri().getPath(), response.statusCode(),
                        abbreviate(response.body()));

        if (response.statusCode() == 403) {
            return base + """

                    Das weist auf den Socket-Proxy hin: Dieser Endpunkt ist dort nicht \
                    freigegeben. Siehe docker-socket-proxy in der Compose-Datei.""";
        }
        return base;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body.strip() : body.substring(0, 300).strip() + "...";
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T readJson(String body, Class<T> type) {
        return objectMapper.readValue(body, type);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
