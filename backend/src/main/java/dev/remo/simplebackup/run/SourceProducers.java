package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Findet den Produzenten zu einer Quelle. */
@Component
class SourceProducers {

    private final Map<SourceType, SourceProducer> byType;

    SourceProducers(List<SourceProducer> producers) {
        this.byType = producers.stream()
                .collect(Collectors.toUnmodifiableMap(SourceProducer::type, Function.identity()));
    }

    PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {

        SourceProducer producer = byType.get(plan.source().type());

        if (producer == null) {
            throw new IllegalStateException(
                    "Quellen vom Typ %s sind noch nicht umgesetzt".formatted(plan.source().type()));
        }
        return producer.prepare(plan, stagingDirectory, listener);
    }
}
