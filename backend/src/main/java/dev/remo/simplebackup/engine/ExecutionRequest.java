package dev.remo.simplebackup.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ein auszufuehrender Schritt.
 *
 * <p>Das Kommando ist eine <b>Argumentliste</b>, kein Kommandostring. Dadurch gibt es keine
 * Shell, die etwas interpretieren koennte -- und damit keine Command-Injection ueber
 * Konfigurationsfelder wie Pfade, Hostnamen oder Bucket-Namen. Bei einem Werkzeug, das
 * definitionsgemaess Systembefehle mit fremden Eingaben ausfuehrt, ist das nicht verhandelbar.
 *
 * @param executionId  eindeutige Kennung, wird als Container-Label gesetzt und traegt das
 *                     Wiederanhaengen nach einem Backend-Neustart
 * @param image        Runner-Image, immer mit festem Tag
 * @param command      vollstaendige Argumentliste
 * @param mounts       Verzeichnisse und Volumes
 * @param environment  Umgebungsvariablen. <b>Niemals Geheimnisse</b> -- die waeren dauerhaft
 *                     ueber {@code docker inspect} lesbar. Dafuer ist {@code secretFiles} da.
 * @param secretFiles  Dateiname unterhalb von {@code /run/secrets} auf Klartext. Wird in ein
 *                     tmpfs geschrieben, das mit dem Container verschwindet.
 * @param limits       Ressourcengrenzen
 * @param timeout      Obergrenze fuer die Laufzeit
 * @param labels       zusaetzliche Container-Labels
 */
public record ExecutionRequest(
        String executionId,
        String image,
        List<String> command,
        List<VolumeMount> mounts,
        Map<String, String> environment,
        Map<String, String> secretFiles,
        ResourceLimits limits,
        Duration timeout,
        Map<String, String> labels) {

    /** Pfad, unter dem Geheimnisse im Runner liegen. Ein tmpfs, also ausschliesslich im RAM. */
    public static final String SECRETS_DIRECTORY = "/run/secrets";

    public ExecutionRequest {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image darf nicht leer sein");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command darf nicht leer sein");
        }
        if (command.stream().anyMatch(argument -> argument == null)) {
            throw new IllegalArgumentException("command darf kein null-Argument enthalten");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout muss positiv sein");
        }

        executionId = executionId == null ? UUID.randomUUID().toString() : executionId;
        limits = limits == null ? ResourceLimits.NONE : limits;

        // Unveraenderliche Kopien: Der Aufrufer soll eine Anfrage nicht nachtraeglich
        // veraendern koennen, waehrend sie bereits ausgefuehrt wird.
        command = List.copyOf(command);
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        secretFiles = secretFiles == null ? Map.of() : Map.copyOf(secretFiles);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public static Builder builder(String image, String... command) {
        return new Builder(image, List.of(command));
    }

    /** Vollstaendiger Pfad, unter dem ein Geheimnis im Runner erscheint. */
    public String secretPath(String fileName) {
        if (!secretFiles.containsKey(fileName)) {
            throw new IllegalArgumentException("Kein Geheimnis mit dem Namen " + fileName);
        }
        return SECRETS_DIRECTORY + "/" + fileName;
    }

    /**
     * Bewusst ohne {@code environment} und {@code secretFiles}: Diese Darstellung landet in
     * Logs und Fehlermeldungen.
     */
    @Override
    public String toString() {
        return "ExecutionRequest[id=%s, image=%s, command=%s, mounts=%d, secrets=%d]"
                .formatted(executionId, image, command, mounts.size(), secretFiles.size());
    }

    public static final class Builder {
        private String executionId;
        private final String image;
        private final List<String> command;
        private final java.util.ArrayList<VolumeMount> mounts = new java.util.ArrayList<>();
        private final java.util.LinkedHashMap<String, String> environment = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, String> secretFiles = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
        private ResourceLimits limits = ResourceLimits.NONE;
        private Duration timeout = Duration.ofHours(6);

        private Builder(String image, List<String> command) {
            this.image = image;
            this.command = command;
        }

        public Builder executionId(String value) {
            this.executionId = value;
            return this;
        }

        public Builder mount(VolumeMount mount) {
            this.mounts.add(mount);
            return this;
        }

        public Builder env(String name, String value) {
            this.environment.put(name, value);
            return this;
        }

        public Builder secretFile(String fileName, String plaintext) {
            this.secretFiles.put(fileName, plaintext);
            return this;
        }

        public Builder label(String name, String value) {
            this.labels.put(name, value);
            return this;
        }

        public Builder limits(ResourceLimits value) {
            this.limits = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public ExecutionRequest build() {
            return new ExecutionRequest(executionId, image, command, mounts, environment,
                    secretFiles, limits, timeout, labels);
        }
    }
}
