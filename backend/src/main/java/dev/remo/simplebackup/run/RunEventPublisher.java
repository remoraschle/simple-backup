package dev.remo.simplebackup.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Verteilt die Ereignisse eines laufenden Backups an die Oberflaeche.
 *
 * <p>Server-Sent Events statt WebSocket: Der Datenstrom geht nur in eine Richtung, und SSE
 * bringt Wiederverbindung und Zeilenformat schon mit. Ein WebSocket waere mehr Technik fuer
 * dieselbe Aufgabe.
 *
 * <p>Es koennen mehrere Zuschauer an demselben Lauf haengen -- zwei offene Browserfenster
 * sind der Normalfall, nicht die Ausnahme.
 */
@Component
public class RunEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RunEventPublisher.class);

    /** Zuschauer je Lauf. */
    private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * Meldet einen neuen Zuschauer an.
     *
     * <p>Das Zeitlimit ist grosszuegig: Ein Backup kann Stunden laufen, und ein
     * Fortschrittsbalken, der nach fuenf Minuten abbricht, waere nutzlos.
     */
    public SseEmitter subscribe(UUID runId, long timeoutMillis) {
        return register(runId, new SseEmitter(timeoutMillis));
    }

    /**
     * Meldet einen fertigen Emitter an.
     *
     * <p>Paketprivat, damit Tests einen eigenen Emitter einhaengen koennen, ohne dass die
     * Erzeugung nach aussen sichtbar werden muss.
     */
    SseEmitter register(UUID runId, SseEmitter emitter) {
        subscribers.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));

        return emitter;
    }

    /** Verteilt ein Ereignis an alle Zuschauer dieses Laufs. */
    public void publish(UUID runId, RunEvent event) {
        List<SseEmitter> emitters = subscribers.get(runId);
        if (emitters == null || emitters.isEmpty()) {
            // Niemand schaut zu. Das ist der Normalfall und kein Grund, etwas zu tun.
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event.eventName()).data(event));
            } catch (IOException | IllegalStateException e) {
                // Ein abgewanderter Zuschauer -- Browserfenster geschlossen, Netz weg.
                // Der Lauf selbst geht davon unberuehrt weiter.
                remove(runId, emitter);
            }
        }
    }

    /** Schliesst den Datenstrom, nachdem der Lauf beendet ist. */
    public void closeStream(UUID runId) {
        List<SseEmitter> emitters = subscribers.remove(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Datenstrom fuer Lauf {} liess sich nicht schliessen: {}", runId, e.getMessage());
            }
        }
    }

    /** Wie viele Zuschauer ein Lauf hat. Fuer Tests und Diagnose. */
    int subscriberCount(UUID runId) {
        return subscribers.getOrDefault(runId, List.of()).size();
    }

    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(runId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                subscribers.remove(runId);
            }
        }
    }
}
