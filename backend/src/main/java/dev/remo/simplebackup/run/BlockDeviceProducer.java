package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.SourceType;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.DeviceAccess;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Zieht ein Abbild eines ganzen Datentraegers.
 *
 * <p><b>Warum ueberhaupt:</b> Manches laesst sich nicht dateiweise sichern -- ein
 * Bootsektor, eine Partitionstabelle, ein Dateisystem, das dieses System gar nicht lesen
 * kann. Dafuer gibt es nur den rohen Weg.
 *
 * <p><b>Warum als Datei im Zwischenverzeichnis und nicht als Strom nach {@code restic
 * backup --stdin}:</b> Ein Plan schreibt auf mehrere Ziele, und ein Strom laesst sich nicht
 * zweimal lesen. Die Quelle einmal zu beschaffen und das Ergebnis auf alle Ziele zu
 * schreiben, ist der Kern dieser Pipeline -- der Preis ist Platz im Zwischenverzeichnis,
 * und den nennt die Oberflaeche beim Anlegen.
 *
 * <p><b>Warum nicht komprimiert:</b> restic komprimiert selbst. Ein vorher durch zstd
 * geschobener Strom waere fuer die Deduplizierung nur noch Rauschen -- zwei Laeufe
 * derselben, kaum veraenderten Platte haetten danach nichts mehr gemeinsam.
 *
 * <p>Gelesen wird ausschliesslich. Das Geraet kommt lesend in den Runner, und {@code dd}
 * schreibt in die andere Richtung.
 */
@Component
class BlockDeviceProducer implements SourceProducer {

    private static final Logger log = LoggerFactory.getLogger(BlockDeviceProducer.class);

    /**
     * Blockgroesse fuers Lesen.
     *
     * <p>Der Standard von 512 Byte macht aus einer Platte Millionen von Systemaufrufen. Vier
     * Megabyte sind gross genug, dass das nicht mehr ins Gewicht faellt, und klein genug,
     * dass der Speicherbedarf nicht auffaellt.
     */
    private static final String BLOCK_SIZE = "4M";

    private final BackupExecutor executor;
    private final ResticTargets targets;
    private final DeviceAccess devices;
    private final SecretRedactor redactor;
    private final RunProperties properties;

    BlockDeviceProducer(BackupExecutor executor, ResticTargets targets, DeviceAccess devices,
            SecretRedactor redactor, RunProperties properties) {

        this.executor = executor;
        this.targets = targets;
        this.devices = devices;
        this.redactor = redactor;
        this.properties = properties;
    }

    @Override
    public SourceType type() {
        return SourceType.BLOCK_DEVICE;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {

        SourceConfig.BlockDevice source = (SourceConfig.BlockDevice) plan.source();

        // Erneut geprueft, obwohl schon beim Anlegen geprueft wurde: Die Freigabeliste kann
        // sich seither geaendert haben, und eine Quelle aus der Zeit davor darf daraus kein
        // Recht ableiten.
        devices.require(source.device());

        VolumeMount staging = targets.translate(stagingDirectory, false);
        String image = staging.target() + "/" + source.imageName();

        List<String> command = new ArrayList<>(List.of("dd",
                "if=" + source.device(),
                "of=" + image,
                "bs=" + BLOCK_SIZE,
                // Sonst meldet dd nur am Ende etwas, und ein Lauf ueber Stunden sieht aus
                // wie einer, der haengt.
                "status=progress"));

        if (source.sparse()) {
            command.add("conv=sparse");
        }

        run(plan, source, command, staging, listener);

        return new PreparedSource(List.of(staging.target()), List.of(staging), List.of(),
                stagingDirectory);
    }

    private void run(ExecutablePlan plan, SourceConfig.BlockDevice source, List<String> command,
            VolumeMount staging, RunProgressListener listener) {

        String description = "Abbild von " + source.device();

        ExecutionRequest request = ExecutionRequest.builder(executor.defaultEnvironment(),
                        command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(properties.acquireTimeout())
                .mount(staging)
                .device(source.device())
                .label("simple-backup.plan-id", plan.planId().toString())
                .build();

        UUID stepId = listener.stepStarted(StepKind.ACQUIRE, null, description,
                executor.defaultEnvironment(), redactor.redact(request.command()));

        try {
            var running = executor.start(request, listener::logLine);
            ExecutionResult result = running.awaitCompletion(properties.acquireTimeout());

            listener.stepFinished(stepId, result.isSuccess() ? StepStatus.SUCCESS : StepStatus.FAILED,
                    result.exitCode(), result.isSuccess() ? description : result.lastError());

            if (!result.isSuccess()) {
                // Ein halbes Abbild ist kein Abbild. Es trotzdem zu sichern hiesse, einen
                // gruenen Lauf zu melden und im Ernstfall nichts in der Hand zu haben.
                throw new IllegalStateException("%s fehlgeschlagen: %s"
                        .formatted(description, result.lastError()));
            }
            log.debug("{} abgeschlossen", description);

        } catch (ExecutionException e) {
            String message = redactor.redact(String.valueOf(e.getMessage()));
            listener.stepFinished(stepId, StepStatus.FAILED, null, message);
            throw new IllegalStateException("%s fehlgeschlagen: %s".formatted(description, message), e);
        }
    }
}
