package dev.remo.simplebackup.engine.docker;

import dev.remo.simplebackup.engine.VolumeMount;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ermittelt, welche Verzeichnisse dem Backend-Container zur Verfuegung stehen.
 *
 * <p>Das ist die Grundlage der Host-Pfad-Uebersetzung: Statt einen zweiten, von Hand
 * gepflegten Pfad-Katalog zu fuehren, liest das Backend seine eigene Mount-Tabelle. Was in
 * der Compose-Datei steht, gilt damit automatisch -- und eine Aenderung dort kann nicht still
 * mit der Anwendungskonfiguration auseinanderlaufen.
 */
public class SelfInspector {

    private static final Logger log = LoggerFactory.getLogger(SelfInspector.class);

    private final DockerApiClient client;

    public SelfInspector(DockerApiClient client) {
        this.client = client;
    }

    /**
     * Liest die eigene Mount-Tabelle.
     *
     * @param containerId eigene Container-Kennung
     * @return die Einhaengungen, oder leer, wenn sich der Container nicht befragen laesst
     */
    public Optional<List<VolumeMount>> readOwnMounts(String containerId) {
        return client.inspect(containerId)
                .map(DockerDto.ContainerDetails::mounts)
                .map(SelfInspector::toVolumeMounts);
    }

    /**
     * Wandelt die Mount-Tabelle der Docker-API in das eigene Modell um.
     *
     * <p>Bei einem Volume zaehlt der Name, nicht der Pfad unter {@code /var/lib/docker}: Nur
     * ueber den Namen laesst sich dasselbe Volume in einen anderen Container einhaengen.
     */
    static List<VolumeMount> toVolumeMounts(List<DockerDto.MountPoint> mounts) {
        if (mounts == null) {
            return List.of();
        }
        return mounts.stream()
                .filter(mount -> mount.destination() != null)
                .map(mount -> mount.isVolume()
                        ? new VolumeMount(mount.name(), mount.destination(), mount.isReadOnly(), true)
                        : new VolumeMount(mount.source(), mount.destination(), mount.isReadOnly(), false))
                .toList();
    }

    /**
     * Liest ausdruecklich konfigurierte Einhaengungen.
     *
     * <p>Format wie bei Docker: {@code /host/pfad:/container/pfad[:ro]}. Eine fehlerhafte
     * Angabe wird beim Start abgelehnt statt stillschweigend uebergangen -- eine uebergangene
     * Einhaengung faellt sonst erst auf, wenn eine Quelle sich nicht anlegen laesst.
     */
    public static List<VolumeMount> parseMounts(List<String> specifications) {
        return specifications.stream().map(SelfInspector::parseMount).toList();
    }

    private static VolumeMount parseMount(String specification) {
        String[] parts = specification.split(":");

        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("""
                    "%s" ist keine gueltige Einhaengung. Erwartet wird \
                    /host/pfad:/container/pfad oder /host/pfad:/container/pfad:ro."""
                    .formatted(specification));
        }
        boolean readOnly = parts.length == 3 && "ro".equals(parts[2]);
        boolean namedVolume = !parts[0].startsWith("/");

        return new VolumeMount(parts[0], parts[1], readOnly, namedVolume);
    }

    /**
     * Findet die eigene Container-Kennung.
     *
     * <p>Docker setzt den Hostnamen eines Containers auf dessen gekuerzte Kennung. Das ist
     * der einfachste verlaessliche Weg -- es sei denn, in der Compose-Datei wurde ein eigener
     * Hostname gesetzt. Fuer diesen Fall gibt es die ausdrueckliche Konfiguration.
     *
     * @param configuredId ausdruecklich gesetzte Kennung, gewinnt immer
     * @param environment  Prozessumgebung
     */
    public static Optional<String> detectContainerId(String configuredId, Map<String, String> environment) {
        if (configuredId != null && !configuredId.isBlank()) {
            return Optional.of(configuredId);
        }

        String hostname = environment.get("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            log.debug("Kein HOSTNAME gesetzt -- die Anwendung laeuft vermutlich nicht in einem Container");
            return Optional.empty();
        }
        return Optional.of(hostname);
    }
}
