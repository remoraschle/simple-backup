package dev.remo.simplebackup.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.shared.SecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Testet gegen echte Prozesse, nicht gegen Attrappen.
 *
 * <p>Zeitlimit, Abbruch und das Aufraeumen von Geheimnissen lassen sich nur an echten
 * Prozessen ueberzeugend pruefen -- eine Attrappe wuerde genau das bestaetigen, was man ihr
 * beigebracht hat.
 */
class LocalProcessExecutorTest {

    private LocalProcessExecutor executor;
    private List<String> logLines;

    @BeforeEach
    void setUp() {
        executor = new LocalProcessExecutor(new SecretRedactor());
        logLines = new CopyOnWriteArrayList<>();
    }

    private ExecutionResult run(ExecutionRequest request) {
        return executor.start(request, logLines::add).awaitCompletion(request.timeout());
    }

    @Test
    @DisplayName("Ein erfolgreicher Lauf meldet SUCCESS und Rueckgabewert 0")
    void reportsSuccess() {
        var result = run(ExecutionRequest.builder("egal", "echo", "fertig")
                .timeout(Duration.ofSeconds(10)).build());

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.lastError()).isNull();
        assertThat(logLines).containsExactly("fertig");
    }

    @Test
    @DisplayName("Ein Rueckgabewert ungleich 0 fuehrt zu FAILED")
    void reportsFailure() {
        var result = run(ExecutionRequest.builder("egal", "false")
                .timeout(Duration.ofSeconds(10)).build());

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("Die letzte Ausgabezeile wird als Fehlerhinweis festgehalten")
    void keepsLastLineAsErrorHint() {
        // Bei einem fehlgeschlagenen Lauf ist die letzte Zeile fast immer die aussagekraeftige.
        var result = run(ExecutionRequest.builder("egal", "sh", "-c", "echo erste; echo letzte; exit 3")
                .timeout(Duration.ofSeconds(10)).build());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.lastError()).isEqualTo("letzte");
    }

    @Test
    @DisplayName("Sonderzeichen in Argumenten bleiben Argumente und werden nicht ausgefuehrt")
    void doesNotInterpretShellMetacharacters() {
        // Der Kern des Injection-Schutzes. Gaebe es eine Shell, waere hier ein zweiter Befehl
        // ausgefuehrt worden -- bei einem Werkzeug, das Pfade aus der Konfiguration an
        // Systembefehle weiterreicht, waere das die gefaehrlichste denkbare Luecke.
        var result = run(ExecutionRequest.builder("egal", "echo", "harmlos; echo BOESE")
                .timeout(Duration.ofSeconds(10)).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(logLines).containsExactly("harmlos; echo BOESE");
        assertThat(logLines).noneMatch(line -> line.equals("BOESE"));
    }

    @Test
    @DisplayName("Ausgabe wird waehrend des Laufs gestreamt, nicht erst am Ende")
    @Timeout(20)
    void streamsOutputWhileRunning() throws Exception {
        var request = ExecutionRequest.builder("egal", "sh", "-c", "echo sofort; sleep 5; echo spaeter")
                .timeout(Duration.ofSeconds(15)).build();

        var running = executor.start(request, logLines::add);

        // Die erste Zeile muss lange vor Prozessende da sein. Sonst liefe bei einem
        // vierstuendigen Backup der Fortschrittsbalken erst hinterher.
        Thread.sleep(1500);
        assertThat(logLines).containsExactly("sofort");

        running.awaitCompletion(Duration.ofSeconds(15));
        assertThat(logLines).containsExactly("sofort", "spaeter");
    }

    @Test
    @DisplayName("Ein ueberschrittenes Zeitlimit beendet den Prozess und meldet TIMEOUT")
    @Timeout(30)
    void enforcesTimeout() {
        var request = ExecutionRequest.builder("egal", "sleep", "60")
                .timeout(Duration.ofSeconds(2)).build();

        var running = executor.start(request, logLines::add);
        var result = running.awaitCompletion(Duration.ofSeconds(2));

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.exitCode()).isNull();
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("Ein Abbruch beendet auch Enkelprozesse")
    @Timeout(30)
    void cancelTerminatesDescendants() throws Exception {
        // Ohne die Kinder mitzunehmen bliebe etwa ein von einem Wrapper gestartetes rsync
        // am Leben und schriebe weiter ins Ziel.
        var request = ExecutionRequest.builder("egal", "sh", "-c", "sleep 120 & wait")
                .timeout(Duration.ofSeconds(60)).build();

        var running = executor.start(request, logLines::add);
        Thread.sleep(500);
        long pid = Long.parseLong(running.id());

        running.cancel();
        var result = running.awaitCompletion(Duration.ofSeconds(20));

        assertThat(result.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisplayName("Abbruch eines bereits beendeten Laufs ist folgenlos")
    void cancelIsIdempotent() {
        var running = executor.start(
                ExecutionRequest.builder("egal", "true").timeout(Duration.ofSeconds(10)).build(),
                logLines::add);
        running.awaitCompletion(Duration.ofSeconds(10));

        running.cancel();
        running.cancel();
    }

    @Test
    @DisplayName("Geheimnisse liegen als Datei vor und verschwinden nach dem Lauf")
    @Timeout(20)
    void writesSecretsToFileAndRemovesThem() {
        // Die Shell ist hier zulaessig: Das Kommando stammt aus dem Test, nicht aus einer
        // Eingabe. Ohne sie liesse sich der erst zur Laufzeit erzeugte Pfad nicht aufloesen.
        //
        // Die Geheimnisdatei endet bewusst ohne Zeilenumbruch -- restic --password-file
        // erwartet genau das -- deshalb je ein eigenes echo pro Ausgabe.
        var request = ExecutionRequest.builder("egal", "sh", "-c",
                        "echo inhalt:$(cat $SIMPLEBACKUP_SECRETS_DIR/token); "
                                + "echo rechte:$(stat -c %a $SIMPLEBACKUP_SECRETS_DIR/token); "
                                + "echo verzeichnis:$SIMPLEBACKUP_SECRETS_DIR")
                .secretFile("token", "streng-geheimer-wert")
                .timeout(Duration.ofSeconds(10))
                .build();

        var result = run(request);

        assertThat(result.isSuccess()).isTrue();

        // Nur der eigene Benutzer darf die Datei lesen.
        assertThat(valueOf("rechte:")).isEqualTo("600");

        // Nach dem Lauf darf nichts zurueckbleiben: Geheimnisse leben nicht laenger als der
        // Schritt, der sie braucht.
        assertThat(Files.exists(Path.of(valueOf("verzeichnis:")))).isFalse();
    }

    @Test
    @DisplayName("Ein Geheimnis erscheint nicht im Log, auch wenn das Werkzeug es ausgibt")
    @Timeout(20)
    void redactsSecretsFromOutput() {
        var request = ExecutionRequest.builder("egal", "sh", "-c",
                        "echo inhalt:$(cat $SIMPLEBACKUP_SECRETS_DIR/token)")
                .secretFile("token", "streng-geheimer-wert")
                .timeout(Duration.ofSeconds(10))
                .build();

        run(request);

        assertThat(logLines).isNotEmpty();
        assertThat(String.join("\n", logLines)).doesNotContain("streng-geheimer-wert").contains("***");
    }

    /** Liest den Wert hinter einem Praefix aus den gesammelten Logzeilen. */
    private String valueOf(String prefix) {
        return logLines.stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Keine Zeile mit Praefix '%s' in %s".formatted(prefix, logLines)));
    }

    @Test
    @DisplayName("Ein unbekanntes Kommando fuehrt zu einer erklaerenden Ausnahme")
    void failsClearlyOnMissingCommand() {
        var request = ExecutionRequest.builder("egal", "gibt-es-ganz-sicher-nicht-xyz")
                .timeout(Duration.ofSeconds(10)).build();

        assertThatThrownBy(() -> executor.start(request, logLines::add))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("liess sich nicht starten");
    }

    @Test
    @DisplayName("Wiederanhaengen ist lokal nicht moeglich und behauptet das auch nicht")
    void reattachAlwaysReturnsEmpty() {
        // Kindprozesse sterben mit dem Backend. Genau dieser Unterschied rechtfertigt im
        // Betrieb das Container-Modell.
        assertThat(executor.reattach("irgendeine-id", LogSink.discarding())).isEmpty();
    }
}
