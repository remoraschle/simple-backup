package dev.remo.simplebackup.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionRequestTest {

    @Test
    @DisplayName("Das Kommando bleibt eine Argumentliste und wird nie zu einem Shell-String")
    void keepsCommandAsArgumentList() {
        // Der Kern des Injection-Schutzes: Ein Dateiname mit Semikolon und Anfuehrungszeichen
        // ist ein Argument, kein Befehlstrenner. Es gibt keine Shell, die ihn interpretiert.
        var request = ExecutionRequest.builder("runner:1", "restic", "backup", "/daten/; rm -rf /").build();

        assertThat(request.command()).containsExactly("restic", "backup", "/daten/; rm -rf /");
    }

    @Test
    @DisplayName("Eine gebaute Anfrage laesst sich nachtraeglich nicht mehr veraendern")
    void isImmutableAfterConstruction() {
        var mounts = new ArrayList<VolumeMount>();
        mounts.add(VolumeMount.readOnlyPath("/srv/daten", "/quelle"));

        var request = new ExecutionRequest("id", "runner:1", List.of("restic"), mounts,
                null, null, null, null, Duration.ofMinutes(5), null);

        mounts.add(VolumeMount.writablePath("/etc", "/etc"));

        assertThat(request.mounts()).hasSize(1);
        assertThatThrownBy(() -> request.command().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Ohne Kommando oder Image wird die Anfrage abgelehnt")
    void rejectsIncompleteRequest() {
        assertThatThrownBy(() -> ExecutionRequest.builder("runner:1").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");

        assertThatThrownBy(() -> ExecutionRequest.builder("", "restic").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image");
    }

    @Test
    @DisplayName("Ein Zeitlimit ist Pflicht und muss positiv sein")
    void requiresPositiveTimeout() {
        // Ohne Zeitlimit koennte ein haengender Schritt einen Plan dauerhaft blockieren.
        assertThatThrownBy(() -> ExecutionRequest.builder("runner:1", "restic")
                .timeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Ohne eigene Kennung wird eine erzeugt")
    void generatesExecutionId() {
        var request = ExecutionRequest.builder("runner:1", "restic").build();

        assertThat(request.executionId()).isNotBlank();
    }

    @Test
    @DisplayName("Geheimnisse erscheinen unter /run/secrets, nicht in der Umgebung")
    void secretsAreFilesNotEnvironment() {
        var request = ExecutionRequest.builder("runner:1", "restic", "backup")
                .secretFile("restic-password", "sehr-geheim")
                .build();

        assertThat(request.secretPath("restic-password")).isEqualTo("/run/secrets/restic-password");
        assertThat(request.environment()).isEmpty();
    }

    @Test
    @DisplayName("Der Pfad eines unbekannten Geheimnisses wird nicht erfunden")
    void rejectsUnknownSecret() {
        var request = ExecutionRequest.builder("runner:1", "restic").build();

        assertThatThrownBy(() -> request.secretPath("gibtesnicht"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Die Textdarstellung verraet weder Umgebung noch Geheimnisse")
    void toStringHidesSensitiveData() {
        // Diese Darstellung landet in Logs und Fehlermeldungen.
        var request = ExecutionRequest.builder("runner:1", "restic")
                .secretFile("password", "streng-geheim")
                .env("AWS_PROFILE", "backup")
                .build();

        assertThat(request.toString()).doesNotContain("streng-geheim").contains("secrets=1");
    }
}
