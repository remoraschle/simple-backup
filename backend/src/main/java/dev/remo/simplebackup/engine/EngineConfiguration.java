package dev.remo.simplebackup.engine;

import dev.remo.simplebackup.engine.docker.ContainerReaper;
import dev.remo.simplebackup.engine.docker.DockerApiClient;
import dev.remo.simplebackup.engine.docker.DockerJobExecutor;
import dev.remo.simplebackup.engine.docker.DockerProperties;
import dev.remo.simplebackup.engine.docker.DockerRuntime;
import dev.remo.simplebackup.engine.docker.SelfInspector;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Verdrahtung der Ausfuehrungsschicht.
 *
 * <p>Im Betrieb laeuft jeder Schritt in einem Container. Fuer die lokale Entwicklung ohne
 * Docker-Daemon laesst sich mit {@code simplebackup.engine.executor=local} auf Kindprozesse
 * umstellen -- mit den in {@link LocalProcessExecutor} beschriebenen Einschraenkungen.
 */
@Configuration
class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    @Bean
    DockerApiClient dockerApiClient(DockerProperties properties, ObjectMapper objectMapper) {
        return new DockerApiClient(properties, objectMapper);
    }

    @Bean
    SelfInspector selfInspector(DockerApiClient client) {
        return new SelfInspector(client);
    }

    @Bean
    ContainerReaper containerReaper(DockerApiClient client) {
        return new ContainerReaper(client);
    }

    @Bean
    @ConditionalOnProperty(name = "simplebackup.engine.executor", havingValue = "docker", matchIfMissing = true)
    BackupExecutor dockerJobExecutor(DockerApiClient client, DockerProperties properties,
            SecretRedactor redactor) {
        return new DockerJobExecutor(client, properties, redactor);
    }

    @Bean
    @ConditionalOnProperty(name = "simplebackup.engine.executor", havingValue = "local")
    BackupExecutor localProcessExecutor(SecretRedactor redactor) {
        log.warn("""
                Die Ausfuehrung laeuft ueber Kindprozesse statt ueber Container. \
                Ohne Isolierung, ohne Ressourcengrenzen, und laufende Sicherungen \
                ueberstehen einen Neustart nicht. Nur fuer die Entwicklung gedacht.""");
        return new LocalProcessExecutor(redactor);
    }

    /**
     * Der Betriebsmodus des Docker-Daemons.
     *
     * <p>Wird beim Start einmal ermittelt. Ist der Daemon nicht erreichbar, wird von einem
     * Daemon mit Wurzelrechten ausgegangen: Diese Annahme schraenkt keine Funktion ein, die
     * spaeter doch verfuegbar waere.
     */
    @Bean
    DockerRuntime dockerRuntime(DockerApiClient client) {
        try {
            DockerRuntime runtime = DockerRuntime.from(client.systemInfo());
            log.info("Docker {} erkannt, Betrieb {}", runtime.serverVersion(),
                    runtime.rootless() ? "ohne Wurzelrechte (rootless)" : "mit Wurzelrechten");
            return runtime;
        } catch (RuntimeException e) {
            log.warn("Betriebsmodus des Docker-Daemons nicht ermittelbar: {}", e.getMessage());
            return new DockerRuntime(false, "unbekannt");
        }
    }

    /**
     * Die Uebersetzung von Container- auf Host-Pfaden.
     *
     * <p>Laeuft die Anwendung nicht in einem Container, bleibt die Tabelle leer -- dann
     * scheitert jede Uebersetzung mit einer erklaerenden Meldung, statt einen falschen Pfad
     * zu erraten.
     */
    @Bean
    MountTranslator mountTranslator(SelfInspector inspector, DockerProperties properties,
            Environment environment) {

        // Ausdrueckliche Angaben haben Vorrang: Sie sind der Ausweg dort, wo es keine
        // eigene Mount-Tabelle gibt -- etwa in der lokalen Entwicklung.
        if (!properties.mounts().isEmpty()) {
            List<VolumeMount> configured = SelfInspector.parseMounts(properties.mounts());
            log.info("{} ausdruecklich konfigurierte Einhaengungen: {}", configured.size(),
                    configured.stream().map(VolumeMount::target).toList());
            return new MountTranslator(configured);
        }

        List<VolumeMount> mounts = SelfInspector
                .detectContainerId(environment.getProperty("simplebackup.docker.self-container-id"),
                        System.getenv())
                .flatMap(id -> {
                    try {
                        return inspector.readOwnMounts(id);
                    } catch (RuntimeException e) {
                        log.warn("Eigene Mount-Tabelle nicht lesbar: {}", e.getMessage());
                        return java.util.Optional.<List<VolumeMount>>empty();
                    }
                })
                .orElseGet(() -> {
                    log.warn("""
                            Eigene Mount-Tabelle nicht ermittelbar. Sicherungen von Pfaden \
                            sind damit nicht moeglich, weil ein Runner-Container sie nicht \
                            erreichen koennte.""");
                    return List.of();
                });

        if (!mounts.isEmpty()) {
            log.info("{} eigene Einhaengungen erkannt: {}", mounts.size(),
                    mounts.stream().map(VolumeMount::target).toList());
        }
        return new MountTranslator(mounts);
    }
}
