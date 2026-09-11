package dev.remo.simplebackup.engine.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bringt nach einem Neustart des Backends Ordnung in die vorgefundenen Container.
 *
 * <p>Weil die Runner den Neustart ueberleben, gibt es drei Faelle:
 *
 * <ul>
 *   <li><b>Bekannt und laufend</b> -- das Backend haengt sich wieder an. Ein vierstuendiges
 *       Backup ist damit nicht verloren.</li>
 *   <li><b>Bekannt und beendet</b> -- der Rueckgabewert wird noch ausgewertet. Deshalb ist
 *       {@code AutoRemove} abgeschaltet: Ein automatisch entfernter Container haette sein
 *       Ergebnis mitgenommen.</li>
 *   <li><b>Unbekannt</b> -- verwaist, etwa weil die Datenbank zurueckgesetzt wurde. Wird
 *       entfernt, sonst sammeln sich mit jedem Neustart Leichen an.</li>
 * </ul>
 *
 * <p>Der Reaper kennt die Datenbank nicht. Welche Schritte bekannt sind, gibt der Aufrufer
 * vor -- das haelt ihn ohne Datenbank testbar und die Zustaendigkeiten sauber getrennt.
 */
public class ContainerReaper {

    private static final Logger log = LoggerFactory.getLogger(ContainerReaper.class);

    private final DockerApiClient client;

    public ContainerReaper(DockerApiClient client) {
        this.client = client;
    }

    /**
     * @param knownExecutionIds Schritte, die die Anwendung noch kennt
     * @return die Container, um die sich der Aufrufer kuemmern muss
     */
    public List<AdoptableContainer> reap(Set<String> knownExecutionIds) {
        var containers = client.listByLabel(DockerLabels.MANAGED_BY, DockerLabels.MANAGED_BY_VALUE);

        if (containers.isEmpty()) {
            return List.of();
        }
        log.info("{} von dieser Anwendung erzeugte Container vorgefunden", containers.size());

        List<AdoptableContainer> adoptable = new ArrayList<>();

        for (DockerDto.ContainerSummary container : containers) {
            String executionId = container.labels() == null
                    ? null : container.labels().get(DockerLabels.EXECUTION_ID);

            if (executionId == null) {
                // Traegt unsere Markierung, aber keine Zuordnung: nicht mehr zuzuordnen.
                log.warn("Container {} ohne Schritt-Kennung wird entfernt", shortId(container.id()));
                removeQuietly(container.id());
                continue;
            }

            if (knownExecutionIds.contains(executionId)) {
                adoptable.add(new AdoptableContainer(container.id(), executionId, container.isRunning()));
            } else {
                log.info("Verwaisten Container {} für unbekannten Schritt {} entfernt",
                        shortId(container.id()), executionId);
                removeQuietly(container.id());
            }
        }

        long running = adoptable.stream().filter(AdoptableContainer::running).count();
        if (!adoptable.isEmpty()) {
            log.info("{} Schritte laufen weiter, {} sind zwischenzeitlich beendet",
                    running, adoptable.size() - running);
        }
        return List.copyOf(adoptable);
    }

    /**
     * Das Aufraeumen darf den Start nicht verhindern.
     *
     * <p>Ein Container, der sich nicht entfernen laesst, ist ein Ärgernis; ein Backend, das
     * deshalb nicht hochkommt, waere ein Ausfall.
     */
    private void removeQuietly(String containerId) {
        try {
            client.remove(containerId, true);
        } catch (DockerApiException e) {
            log.warn("Container {} liess sich nicht entfernen: {}", shortId(containerId), e.getMessage());
        }
    }

    private static String shortId(String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }
}
