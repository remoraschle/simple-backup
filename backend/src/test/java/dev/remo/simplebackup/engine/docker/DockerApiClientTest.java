package dev.remo.simplebackup.engine.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DockerApiClientTest {

    private FakeDockerApi api;
    private DockerApiClient client;

    @BeforeEach
    void setUp() {
        api = new FakeDockerApi();
        client = new DockerApiClient(
                new DockerProperties(api.baseUrl(), "v1.51", Duration.ofSeconds(2),
                        Duration.ofSeconds(5), null, null, null),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Nested
    @DisplayName("Container-Lebenszyklus")
    class Lifecycle {

        @Test
        @DisplayName("Anlegen sendet die Konfiguration und liefert die Kennung")
        void createsContainer() {
            api.respond("/containers/create", 201, """
                    {"Id":"abc123def456","Warnings":[]}""");

            var request = new DockerDto.CreateContainer(
                    "runner:1.0", List.of("restic", "backup", "/daten"), List.of("TZ=Europe/Zurich"),
                    Map.of("simple-backup.run-id", "lauf-1"), "1000:1000", false, true, true,
                    new DockerDto.HostConfig(List.of("/srv:/quelle:ro"), 512L * 1024 * 1024,
                            1_500_000_000L, "none", false, false, List.of("ALL"), null, null));

            String id = client.createContainer(request, "simple-backup-lauf-1");

            assertThat(id).isEqualTo("abc123def456");

            var recorded = api.lastRequestTo("/containers/create");
            assertThat(recorded.query()).contains("name=simple-backup-lauf-1");

            // Die Docker-API erwartet Feldnamen in Grossschreibung -- eine falsche
            // Schreibweise wuerde stillschweigend ignoriert und der Container liefe ohne
            // Grenzen oder ohne Einhaengungen.
            assertThat(recorded.bodyAsString())
                    .contains("\"Image\":\"runner:1.0\"")
                    .contains("\"Cmd\":[\"restic\",\"backup\",\"/daten\"]")
                    .contains("\"Binds\":[\"/srv:/quelle:ro\"]")
                    .contains("\"NetworkMode\":\"none\"")
                    .contains("\"AutoRemove\":false")
                    .contains("\"User\":\"1000:1000\"")
                    // Ohne TTY, damit der Logstrom gerahmt kommt und Werkzeuge sich wie im
                    // Skript verhalten.
                    .contains("\"Tty\":false");
        }

        @Test
        @DisplayName("Warten liefert den Rueckgabewert des Prozesses")
        void returnsExitCode() {
            api.respond("/containers/abc/wait", 200, """
                    {"StatusCode":3}""");

            assertThat(client.waitForExit("abc", Duration.ofSeconds(5))).isEqualTo(3);
        }

        @Test
        @DisplayName("Ein bereits beendeter Container laesst sich folgenlos beenden")
        void killIgnoresAlreadyStopped() {
            // Docker antwortet mit 409. Das ist kein Fehler: Das Ziel war, dass er nicht
            // mehr laeuft.
            api.respond("/containers/abc/kill", 409, """
                    {"message":"container is not running"}""");

            client.kill("abc", "SIGTERM");
        }

        @Test
        @DisplayName("Ein nicht mehr vorhandener Container laesst sich folgenlos entfernen")
        void removeIgnoresMissingContainer() {
            api.respond("/containers/abc", 404, """
                    {"message":"no such container"}""");

            client.remove("abc", true);
        }

        @Test
        @DisplayName("Entfernen nimmt anonyme Volumes mit")
        void removeDeletesAnonymousVolumes() {
            client.remove("abc", true);

            assertThat(api.lastRequestTo("/containers/abc").query()).contains("v=true");
        }
    }

    @Nested
    @DisplayName("Beobachtung")
    class Observation {

        @Test
        @DisplayName("Ein unbekannter Container liefert leer statt eines Fehlers")
        void inspectReturnsEmptyForUnknownContainer() {
            api.respond("/containers/weg/json", 404, """
                    {"message":"no such container"}""");

            assertThat(client.inspect("weg")).isEmpty();
        }

        @Test
        @DisplayName("Die Mount-Tabelle wird ausgelesen -- Grundlage der Pfad-Uebersetzung")
        void readsMountTable() {
            api.respond("/containers/self/json", 200, """
                    {
                      "Id": "self",
                      "Name": "/simple-backup-backend",
                      "State": {"Status":"running","Running":true,"ExitCode":0},
                      "Config": {"Labels":{}},
                      "Mounts": [
                        {"Type":"bind","Source":"/srv/fotos","Destination":"/sources/fotos","RW":false},
                        {"Type":"volume","Name":"staging","Source":"/var/lib/docker/volumes/staging/_data",
                         "Destination":"/var/lib/simple-backup/staging","RW":true}
                      ]
                    }""");

            var details = client.inspect("self").orElseThrow();

            assertThat(details.mounts()).hasSize(2);
            assertThat(details.mounts().getFirst().source()).isEqualTo("/srv/fotos");
            assertThat(details.mounts().getFirst().destination()).isEqualTo("/sources/fotos");
            assertThat(details.mounts().getFirst().isVolume()).isFalse();
            assertThat(details.mounts().get(1).isVolume()).isTrue();
            assertThat(details.mounts().get(1).name()).isEqualTo("staging");
        }

        @Test
        @DisplayName("Die Suche nach Label findet auch beendete Container")
        void listsContainersByLabelIncludingStopped() {
            // Sonst bliebe ein waehrend des Neustarts beendeter Lauf unbemerkt.
            api.respond("/containers/json", 200, """
                    [{"Id":"a1","State":"running","Labels":{"simple-backup.managed-by":"simple-backup"}},
                     {"Id":"b2","State":"exited","Labels":{"simple-backup.managed-by":"simple-backup"}}]""");

            var containers = client.listByLabel("simple-backup.managed-by", "simple-backup");

            assertThat(containers).hasSize(2);
            assertThat(containers.getFirst().isRunning()).isTrue();
            assertThat(containers.get(1).isRunning()).isFalse();

            var recorded = api.lastRequestTo("/containers/json");
            assertThat(recorded.query()).contains("all=true");
            assertThat(java.net.URLDecoder.decode(recorded.query(), StandardCharsets.UTF_8))
                    .contains("""
                            {"label":["simple-backup.managed-by=simple-backup"]}""");
        }

        @Test
        @DisplayName("Der Logstrom kommt gerahmt an und laesst sich dekodieren")
        void streamsLogs() throws Exception {
            byte[] frame = new byte[] {1, 0, 0, 0, 0, 0, 0, 6, 'h', 'a', 'l', 'l', 'o', '\n'};
            api.respondRaw("/containers/abc/logs", 200, frame);

            var lines = new java.util.ArrayList<String>();
            try (var stream = client.openLogStream("abc", true)) {
                DockerLogStreamDecoder.decode(stream, lines::add);
            }

            assertThat(lines).containsExactly("hallo");
            assertThat(api.lastRequestTo("/containers/abc/logs").query())
                    .contains("stdout=true").contains("stderr=true").contains("follow=true");
        }
    }

    @Nested
    @DisplayName("Dateien")
    class Files {

        @Test
        @DisplayName("Geheimnisse werden als TAR uebertragen, mit Rechten nur fuer den Eigentuemer")
        void transfersSecretsAsTar() throws Exception {
            client.copyFilesInto("abc", "/run/secrets",
                    Map.of("restic-password", "streng-geheim"), 0600);

            var recorded = api.lastRequestTo("/containers/abc/archive");
            assertThat(recorded.method()).isEqualTo("PUT");
            assertThat(java.net.URLDecoder.decode(recorded.query(), StandardCharsets.UTF_8))
                    .contains("path=/run/secrets");

            try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(recorded.body()))) {
                var entry = tar.getNextEntry();

                assertThat(entry.getName()).isEqualTo("restic-password");
                // Nur der Eigentuemer darf lesen und schreiben.
                assertThat(entry.getMode() & 0777).isEqualTo(0600);
                assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("streng-geheim");
                assertThat(tar.getNextEntry()).isNull();
            }
        }

        @Test
        @DisplayName("Mehrere Geheimnisse landen in einem Archiv")
        void transfersMultipleSecrets() throws Exception {
            client.copyFilesInto("abc", "/run/secrets",
                    Map.of("password", "eins", "ssh-key", "zwei"), 0600);

            var recorded = api.lastRequestTo("/containers/abc/archive");
            var names = new java.util.ArrayList<String>();
            try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(recorded.body()))) {
                for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                    names.add(entry.getName());
                }
            }
            assertThat(names).containsExactlyInAnyOrder("password", "ssh-key");
        }
    }

    @Nested
    @DisplayName("System")
    class System {

        @Test
        @DisplayName("Rootless Docker wird an den Sicherheitsoptionen erkannt")
        void detectsRootlessDocker() {
            api.respond("/info", 200, """
                    {"ServerVersion":"29.3.1","OperatingSystem":"Ubuntu",
                     "SecurityOptions":["name=seccomp,profile=builtin","name=rootless","name=cgroupns"]}""");

            assertThat(client.systemInfo().securityOptions()).contains("name=rootless");
        }

        @Test
        @DisplayName("Vorhandene Images werden erkannt")
        void detectsExistingImage() {
            api.respond("/images/json", 200, """
                    [{"RepoTags":["runner:1.0"]}]""");

            assertThat(client.imageExists("runner:1.0")).isTrue();
        }

        @Test
        @DisplayName("Ein fehlendes Image wird erkannt statt angenommen")
        void detectsMissingImage() {
            api.respond("/images/json", 200, "[]");

            assertThat(client.imageExists("runner:1.0")).isFalse();
        }

        @Test
        @DisplayName("Ein Image ohne Tag wird als latest geladen")
        void pullsUntaggedImageAsLatest() {
            client.pullImage("ghcr.io/remo/runner", Duration.ofSeconds(5));

            var query = java.net.URLDecoder.decode(
                    api.lastRequestTo("/images/create").query(), StandardCharsets.UTF_8);
            assertThat(query).contains("fromImage=ghcr.io/remo/runner").contains("tag=latest");
        }

        @Test
        @DisplayName("Ein Port in der Registry-Adresse wird nicht als Tag missverstanden")
        void doesNotConfuseRegistryPortWithTag() {
            client.pullImage("registry.local:5000/runner:2.1", Duration.ofSeconds(5));

            var query = java.net.URLDecoder.decode(
                    api.lastRequestTo("/images/create").query(), StandardCharsets.UTF_8);
            assertThat(query).contains("fromImage=registry.local:5000/runner").contains("tag=2.1");
        }

        @Test
        @DisplayName("Ein Fehler im Fortschrittsstrom gilt trotz HTTP 200 als Fehlschlag")
        void treatsErrorInPullStreamAsFailure() {
            // Docker meldet beim Laden von Images Fehler im Nutzdatenstrom, nicht im Status.
            api.respond("/images/create", 200, """
                    {"status":"Pulling"}
                    {"error":"manifest unknown"}""");

            assertThatThrownBy(() -> client.pullImage("runner:gibtsnicht", Duration.ofSeconds(5)))
                    .isInstanceOf(DockerApiException.class)
                    .hasMessageContaining("liess sich nicht laden");
        }
    }

    @Nested
    @DisplayName("Fehlerbehandlung")
    class ErrorHandling {

        @Test
        @DisplayName("Ein 403 weist auf den Socket-Proxy hin statt nur auf den Statuscode")
        void explainsProxyRejection() {
            // Der haeufigste Konfigurationsfehler im Betrieb.
            api.respond("/containers/create", 403, """
                    {"message":"Forbidden"}""");

            assertThatThrownBy(() -> client.createContainer(minimalRequest(), "x"))
                    .isInstanceOf(DockerApiException.class)
                    .hasMessageContaining("Socket-Proxy")
                    .hasMessageContaining("nicht freigegeben")
                    .satisfies(e -> assertThat(((DockerApiException) e).isForbiddenByProxy()).isTrue());
        }

        @Test
        @DisplayName("Eine nicht erreichbare API nennt Adresse und wahrscheinliche Ursache")
        void explainsUnreachableApi() {
            var unreachable = new DockerApiClient(
                    new DockerProperties("http://127.0.0.1:1", "v1.51", Duration.ofMillis(500),
                            Duration.ofSeconds(1), null, null, null),
                    new ObjectMapper());

            assertThatThrownBy(() -> unreachable.systemInfo())
                    .isInstanceOf(DockerApiException.class)
                    .hasMessageContaining("nicht erreichbar")
                    .hasMessageContaining("DOCKER_HOST");
        }

        @Test
        @DisplayName("Eine lange Fehlerantwort wird gekuerzt")
        void abbreviatesLongErrorBodies() {
            api.respond("/containers/create", 500, "x".repeat(5000));

            assertThatThrownBy(() -> client.createContainer(minimalRequest(), "x"))
                    .isInstanceOf(DockerApiException.class)
                    .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(500));
        }

        private DockerDto.CreateContainer minimalRequest() {
            return new DockerDto.CreateContainer("runner:1", List.of("true"), List.of(), Map.of(),
                    null, false, true, true,
                    new DockerDto.HostConfig(List.of(), null, null, null, false, false, null, null,
                            null));
        }
    }

    @Nested
    @DisplayName("Konfiguration")
    class Configuration {

        @Test
        @DisplayName("DOCKER_HOST im tcp-Format wird auf http umgestellt")
        void normalizesTcpScheme() {
            // DOCKER_HOST wird gewoehnlich als tcp:// gesetzt, HTTP-Clients kennen das nicht.
            var properties = new DockerProperties("tcp://dockerproxy:2375", null, null, null, null, null, null);

            assertThat(properties.host()).isEqualTo("http://dockerproxy:2375");
            assertThat(properties.baseUrl()).isEqualTo("http://dockerproxy:2375/v1.51");
        }

        @Test
        @DisplayName("Ein abschliessender Schraegstrich fuehrt nicht zu doppelten Trennern")
        void stripsTrailingSlash() {
            assertThat(new DockerProperties("http://host:2375/", null, null, null, null, null, null).baseUrl())
                    .isEqualTo("http://host:2375/v1.51");
        }

        @Test
        @DisplayName("Der Runner laeuft als unprivilegierter Benutzer")
        void defaultsToUnprivilegedUser() {
            assertThat(new DockerProperties(null, null, null, null, null, null, null).runnerUser())
                    .isEqualTo("1000:1000");
        }
    }
}
