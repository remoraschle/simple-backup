package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunEventPublisherTest {

    private RunEventPublisher publisher;
    private UUID runId;

    @BeforeEach
    void setUp() {
        publisher = new RunEventPublisher();
        runId = UUID.randomUUID();
    }

    /** Ein Emitter, der festhaelt, was er bekommen haette. */
    private static class CapturingEmitter extends SseEmitter {

        private final List<Object> received = new ArrayList<>();
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            received.add(builder);
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    @DisplayName("Ein Ereignis erreicht alle Zuschauer desselben Laufs")
    void deliversToAllSubscribers() {
        // Zwei offene Browserfenster sind der Normalfall, nicht die Ausnahme.
        var first = new CapturingEmitter();
        var second = new CapturingEmitter();
        register(first, second);

        publisher.publish(runId, new RunEvent.Log("scanning..."));

        assertThat(first.received).hasSize(1);
        assertThat(second.received).hasSize(1);
    }

    @Test
    @DisplayName("Ohne Zuschauer passiert nichts")
    void doesNothingWithoutSubscribers() {
        // Der Normalfall: Niemand schaut zu, das Backup laeuft trotzdem.
        publisher.publish(runId, new RunEvent.Log("scanning..."));

        assertThat(publisher.subscriberCount(runId)).isZero();
    }

    @Test
    @DisplayName("Ereignisse anderer Laeufe erreichen einen Zuschauer nicht")
    void separatesRuns() {
        var emitter = new CapturingEmitter();
        register(emitter);

        publisher.publish(UUID.randomUUID(), new RunEvent.Log("fremder Lauf"));

        assertThat(emitter.received).isEmpty();
    }

    @Test
    @DisplayName("Ein abgewanderter Zuschauer bringt den Lauf nicht zu Fall")
    void survivesBrokenSubscriber() {
        // Browserfenster geschlossen, Netz weg -- der Lauf geht davon unberuehrt weiter.
        var broken = new CapturingEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        var healthy = new CapturingEmitter();
        register(broken, healthy);

        publisher.publish(runId, new RunEvent.Log("weiter geht es"));

        assertThat(healthy.received).hasSize(1);
        // Der kaputte Zuschauer wurde entfernt.
        assertThat(publisher.subscriberCount(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Das Schliessen beendet alle Datenstroeme und raeumt auf")
    void closesAllStreams() {
        var first = new CapturingEmitter();
        var second = new CapturingEmitter();
        register(first, second);

        publisher.closeStream(runId);

        assertThat(first.completed).isTrue();
        assertThat(second.completed).isTrue();
        assertThat(publisher.subscriberCount(runId)).isZero();
    }

    @Test
    @DisplayName("Jede Ereignisart hat einen eigenen Namen im Datenstrom")
    void eventsAreNamed() {
        // Damit die Oberflaeche sie unterscheiden kann, ohne ein Feld auswerten zu muessen.
        assertThat(new RunEvent.Log("x").eventName()).isEqualTo("log");
        assertThat(new RunEvent.Progress(null, 50, null, null, null, null, null).eventName())
                .isEqualTo("progress");
        assertThat(new RunEvent.Step(null, StepKind.TRANSFER, StepStatus.RUNNING, "x").eventName())
                .isEqualTo("step");
        assertThat(new RunEvent.Finished(RunStatus.SUCCESS, null).eventName()).isEqualTo("finished");
    }

    private void register(CapturingEmitter... emitters) {
        for (CapturingEmitter emitter : emitters) {
            publisher.register(runId, emitter);
        }
    }
}
