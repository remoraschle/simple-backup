package dev.remo.simplebackup.engine.docker;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param host           Basisadresse der Docker-API. Zeigt im Betrieb auf den Socket-Proxy,
 *                       niemals direkt auf {@code /var/run/docker.sock} -- wer die API
 *                       unbeschraenkt erreicht, ist auf dem Host faktisch Wurzel.
 * @param apiVersion     Version des API-Pfads, etwa {@code v1.51}
 * @param connectTimeout Zeitlimit fuer den Verbindungsaufbau
 * @param requestTimeout Zeitlimit fuer kurze Anfragen. Gilt nicht fuer das Warten auf das
 *                       Ende eines Laufs und nicht fuer den Logstrom.
 * @param runnerImage    Standard-Runner-Image, immer mit festem Tag
 * @param runnerUser     UID:GID im Runner. Konfigurierbar, weil rootless Docker die
 *                       Kennungen abbildet und eine feste Annahme dort falsch waere.
 * @param mounts         Ausdrueckliche Einhaengungen in der Schreibweise
 *                       {@code /host/pfad:/container/pfad[:ro]}.
 *
 *                       <p>Normalerweise leer: Die Einhaengungen kommen aus der eigenen
 *                       Mount-Tabelle, damit kein zweiter Katalog gepflegt werden muss.
 *                       Gebraucht wird die Angabe dort, wo es keine gibt -- in der lokalen
 *                       Entwicklung ausserhalb eines Containers waere sonst keine einzige
 *                       Quelle anlegbar.
 */
@ConfigurationProperties(prefix = "simplebackup.docker")
public record DockerProperties(
        String host,
        String apiVersion,
        Duration connectTimeout,
        Duration requestTimeout,
        String runnerImage,
        String runnerUser,
        List<String> mounts) {

    public DockerProperties {
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
        host = host == null || host.isBlank() ? "http://localhost:2375" : normalizeHost(host);
        apiVersion = apiVersion == null || apiVersion.isBlank() ? "v1.51" : apiVersion;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        runnerImage = runnerImage == null || runnerImage.isBlank()
                ? "ghcr.io/remoraschle/simple-backup-runner:latest" : runnerImage;
        runnerUser = runnerUser == null || runnerUser.isBlank() ? "1000:1000" : runnerUser;
    }

    /** {@code DOCKER_HOST} wird gewoehnlich als {@code tcp://...} gesetzt, HTTP-Clients brauchen http. */
    private static String normalizeHost(String value) {
        String normalized = value.startsWith("tcp://") ? "http://" + value.substring("tcp://".length()) : value;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /** Basis-URL inklusive API-Version. */
    public String baseUrl() {
        return host + "/" + apiVersion;
    }
}
