package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.notification.Notification;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.Severity;
import dev.remo.simplebackup.restic.ResticMessage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Was gemeldet wird, entscheidet der Plan; wie dringend es klingt, dieser Uebersetzer.
 */
class RunNotifierTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private NotificationService notifications;
    private CatalogService catalog;
    private RunNotifier notifier;

    @BeforeEach
    void setUp() {
        notifications = Mockito.mock(NotificationService.class);
        catalog = Mockito.mock(CatalogService.class);
        notifier = new RunNotifier(notifications, catalog);
    }

    private void planNotifies(NotifyOn notifyOn) {
        Mockito.when(catalog.planNotification(PLAN_ID))
                .thenReturn(Optional.of(new CatalogService.PlanNotification("Nächtliche Sicherung", notifyOn)));
    }

    private Notification published() {
        var captor = ArgumentCaptor.forClass(Notification.class);
        Mockito.verify(notifications).publish(captor.capture());
        return captor.getValue();
    }

    private static BackupRunner.TargetOutcome outcome(String name, boolean successful, String message) {
        return new BackupRunner.TargetOutcome(UUID.randomUUID(), name, successful, false, message,
                (ResticMessage.Summary) null);
    }

    @Test
    @DisplayName("Bei FAILURE wird der Erfolg nicht gemeldet")
    void failureOnlySkipsSuccess() {
        planNotifies(NotifyOn.FAILURE);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.SUCCESS, null, List.of());

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }

    @Test
    @DisplayName("Bei FAILURE wird der Teilerfolg gemeldet")
    void failureIncludesPartial() {
        // Ein Teilerfolg ist kein Erfolg: Ein Ziel hat die Daten nicht.
        planNotifies(NotifyOn.FAILURE);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.PARTIAL, null,
                List.of(outcome("NAS", true, null), outcome("S3", false, "nicht erreichbar")));

        assertThat(published().severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    @DisplayName("Bei NEVER wird gar nichts gemeldet")
    void neverStaysSilent() {
        planNotifies(NotifyOn.NEVER);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.FAILED, "alles kaputt", List.of());

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }

    @Test
    @DisplayName("Bei ALWAYS wird auch der Erfolg gemeldet")
    void alwaysIncludesSuccess() {
        planNotifies(NotifyOn.ALWAYS);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.SUCCESS, null,
                List.of(outcome("NAS", true, null)));

        assertThat(published().severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("Ein Fehlschlag ist kritisch, ein Abbruch nicht")
    void severityDistinguishesFailureFromCancellation() {
        // Wer abbricht, hat sich dafuer entschieden. Als kritisch gemeldet stumpfte das ab.
        planNotifies(NotifyOn.FAILURE);
        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.FAILED, "nichts gesichert", List.of());
        assertThat(published().severity()).isEqualTo(Severity.CRITICAL);

        Mockito.reset(notifications);
        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.CANCELLED, "Abgebrochen", List.of());
        assertThat(published().severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    @DisplayName("Der Text nennt jedes Ziel einzeln")
    void bodyNamesEveryTarget() {
        // Beim Teilerfolg ist genau das die Information: Welches Ziel hat die Daten?
        planNotifies(NotifyOn.FAILURE);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.PARTIAL, "1 von 2 Zielen gescheitert",
                List.of(outcome("NAS", true, null), outcome("S3", false, "nicht erreichbar")));

        Notification notification = published();

        assertThat(notification.title()).contains("Nächtliche Sicherung");
        assertThat(notification.body())
                .contains("NAS: erfolgreich")
                .contains("S3: fehlgeschlagen — nicht erreichbar");
        assertThat(notification.planId()).isEqualTo(PLAN_ID);
        assertThat(notification.runId()).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("Ein Lauf, der nie bis zu einem Ziel kam, wird trotzdem gemeldet")
    void reportsRunsThatNeverReachedATarget() {
        // Gerade dieser Fall: Er sichert nichts und faellt sonst niemandem auf.
        planNotifies(NotifyOn.FAILURE);

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.FAILED, "Plan nicht ladbar", List.of());

        assertThat(published().body()).contains("bevor ein Ziel an der Reihe war");
    }

    @Test
    @DisplayName("Ein geloeschter Plan fuehrt nicht zum Fehler")
    void missingPlanIsNoError() {
        Mockito.when(catalog.planNotification(PLAN_ID)).thenReturn(Optional.empty());

        notifier.runFinished(PLAN_ID, RUN_ID, RunStatus.FAILED, "egal", List.of());

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }
}
