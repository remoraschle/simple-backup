package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die Ableitung des Gesamtzustands aus den Schritten.
 *
 * <p>Der Grund, warum ein Lauf ueberhaupt in Schritte zerfaellt: Gelingt die Sicherung auf
 * das NAS und scheitert die auf S3, ist der Lauf weder ein Erfolg noch ein Fehlschlag. Ein
 * Werkzeug, das hier "erfolgreich" meldete, wuerde beim naechsten Ernstfall ein Ziel
 * anbieten, das seit Wochen leer ist.
 */
class RunStatusDerivationTest {

    private static final UUID TARGET_A = UUID.randomUUID();
    private static final UUID TARGET_B = UUID.randomUUID();

    private final BackupRun run = new BackupRun(UUID.randomUUID(), RunTrigger.SCHEDULE, 1);

    private void transfer(UUID targetId, StepStatus status) {
        RunStep step = run.addStep(StepKind.TRANSFER, targetId, "Sicherung");
        step.finish(status, status == StepStatus.SUCCESS ? 0 : 1, null);
    }

    private void acquire(StepStatus status) {
        run.addStep(StepKind.ACQUIRE, null, "Beschaffung").finish(status, null, null);
    }

    @Test
    @DisplayName("Alle Ziele erfolgreich ergibt SUCCESS")
    void allTargetsSucceed() {
        transfer(TARGET_A, StepStatus.SUCCESS);
        transfer(TARGET_B, StepStatus.SUCCESS);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.SUCCESS);
    }

    @Test
    @DisplayName("Ein Ziel erfolgreich, eines gescheitert ergibt PARTIAL")
    void mixedResultIsPartial() {
        // Der Fall, um den es geht: NAS erreichbar, S3 nicht.
        transfer(TARGET_A, StepStatus.SUCCESS);
        transfer(TARGET_B, StepStatus.FAILED);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(RunStatus.PARTIAL.isProblem()).isTrue();
    }

    @Test
    @DisplayName("Alle Ziele gescheitert ergibt FAILED, nicht PARTIAL")
    void allTargetsFailedIsFailure() {
        transfer(TARGET_A, StepStatus.FAILED);
        transfer(TARGET_B, StepStatus.FAILED);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("Scheitert die Beschaffung, ist der Lauf gescheitert -- unabhaengig von den Zielen")
    void failedAcquisitionIsAlwaysFailure() {
        // Ohne Quelldaten gibt es nichts zu uebertragen; ein scheinbar erfolgreiches Ziel
        // haette eine leere Sicherung geschrieben.
        acquire(StepStatus.FAILED);
        transfer(TARGET_A, StepStatus.SUCCESS);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("Ohne Uebertragungsschritt ist der Lauf gescheitert")
    void noTransferStepIsFailure() {
        // Etwa weil das Repository sich nicht anlegen liess. Kein Schritt bedeutet: nichts
        // gesichert.
        run.addStep(StepKind.PREPARE, TARGET_A, "Repository anlegen")
                .finish(StepStatus.FAILED, 1, "Fehlgeschlagen");

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("Ein abgebrochener Schritt faerbt den ganzen Lauf")
    void cancellationWins() {
        transfer(TARGET_A, StepStatus.SUCCESS);
        run.addStep(StepKind.TRANSFER, TARGET_B, "Sicherung").finish(StepStatus.CANCELLED, null, null);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    @DisplayName("Ein Zeitlimit faerbt den ganzen Lauf")
    void timeoutWins() {
        transfer(TARGET_A, StepStatus.SUCCESS);
        run.addStep(StepKind.TRANSFER, TARGET_B, "Sicherung").finish(StepStatus.TIMEOUT, null, null);

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.TIMEOUT);
    }

    @Test
    @DisplayName("Uebersprungene Ziele zaehlen nicht als Erfolg")
    void skippedTargetIsNotSuccess() {
        transfer(TARGET_A, StepStatus.SUCCESS);
        run.addStep(StepKind.TRANSFER, TARGET_B, "Sicherung").skip("Modus nicht umgesetzt");

        assertThat(run.deriveStatus()).isEqualTo(RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("Schritte werden fortlaufend nummeriert")
    void stepsAreNumbered() {
        run.addStep(StepKind.PREPARE, TARGET_A, "eins");
        run.addStep(StepKind.TRANSFER, TARGET_A, "zwei");

        assertThat(run.getSteps()).extracting(RunStep::getSeq).containsExactly(1, 2);
    }

    @Test
    @DisplayName("Nur problematische Zustaende geben Anlass zur Benachrichtigung")
    void identifiesProblemStates() {
        assertThat(RunStatus.SUCCESS.isProblem()).isFalse();
        assertThat(RunStatus.PARTIAL.isProblem()).isTrue();
        assertThat(RunStatus.FAILED.isProblem()).isTrue();
        assertThat(RunStatus.TIMEOUT.isProblem()).isTrue();
        // Ein Abbruch war gewollt und braucht keine Meldung.
        assertThat(RunStatus.CANCELLED.isProblem()).isFalse();
    }

    @Test
    @DisplayName("Laufende und wartende Zustaende gelten nicht als beendet")
    void identifiesFinishedStates() {
        assertThat(RunStatus.QUEUED.isFinished()).isFalse();
        assertThat(RunStatus.RUNNING.isFinished()).isFalse();
        assertThat(RunStatus.SUCCESS.isFinished()).isTrue();
        assertThat(RunStatus.PARTIAL.isFinished()).isTrue();
    }
}
