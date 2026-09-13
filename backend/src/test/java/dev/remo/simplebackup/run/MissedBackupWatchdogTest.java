package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.remo.simplebackup.catalog.CatalogService;
import dev.remo.simplebackup.catalog.CatalogViews.PlanView;
import dev.remo.simplebackup.catalog.MissedRunPolicy;
import dev.remo.simplebackup.catalog.NotifyOn;
import dev.remo.simplebackup.notification.Notification;
import dev.remo.simplebackup.notification.NotificationService;
import dev.remo.simplebackup.notification.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Der Totmannschalter.
 *
 * <p>Mit fester Uhr geprueft: Der Fall, um den es geht, spielt sich ueber Tage ab, und ein
 * Test, der dafuer tagelang wartet, wird nie ausgefuehrt.
 */
class MissedBackupWatchdogTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private CatalogService catalog;
    private RunRepository runs;
    private NotificationService notifications;
    private MissedBackupWatchdog watchdog;

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(CatalogService.class);
        runs = Mockito.mock(RunRepository.class);
        notifications = Mockito.mock(NotificationService.class);

        watchdog = new MissedBackupWatchdog(catalog, runs, notifications,
                new RunProperties(null, null, 2, null, null, null, null, Duration.ofMinutes(30)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void planExpecting(Integer intervalMinutes, boolean enabled, NotifyOn notifyOn,
            Instant createdAt) {

        Mockito.when(catalog.listPlans()).thenReturn(List.of(new PlanView(PLAN_ID, "Nächtliche Sicherung",
                null, UUID.randomUUID(), "Meine Daten", List.of(), null, "0 0 2 * * *", "Europe/Zurich",
                enabled, 60, 2, MissedRunPolicy.SKIP, notifyOn, intervalMinutes, null,
                createdAt, "SUCCESS", createdAt)));
    }

    private void lastSuccessfulRunAt(Instant finishedAt) {
        BackupRun run = Mockito.mock(BackupRun.class);
        Mockito.when(run.getFinishedAt()).thenReturn(finishedAt);
        Mockito.when(runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(
                Mockito.eq(PLAN_ID), Mockito.anyList())).thenReturn(Optional.of(run));
    }

    private Notification published() {
        var captor = ArgumentCaptor.forClass(Notification.class);
        Mockito.verify(notifications).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Eine ausgebliebene Sicherung wird als kritisch gemeldet")
    void reportsOverdueBackup() {
        // Der gefaehrlichste Zustand: Ein Fehlschlag meldet sich, ein ausgebliebener Lauf
        // schweigt -- und Schweigen haelt man fuer ein gutes Zeichen.
        planExpecting(24 * 60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        lastSuccessfulRunAt(NOW.minus(Duration.ofDays(3)));

        watchdog.checkForMissedBackups();

        Notification notification = published();
        assertThat(notification.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(notification.title()).contains("Nächtliche Sicherung");
        assertThat(notification.planId()).isEqualTo(PLAN_ID);
        assertThat(notification.body()).contains("alle 24 Stunden").contains("3 Tage zurück");
    }

    @Test
    @DisplayName("Eine Sicherung innerhalb des Intervalls loest nichts aus")
    void staysSilentWhileInTime() {
        planExpecting(24 * 60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        lastSuccessfulRunAt(NOW.minus(Duration.ofHours(2)));

        watchdog.checkForMissedBackups();

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }

    @Test
    @DisplayName("Eine kleine Verspaetung wird geduldet")
    void toleratesSmallDelay() {
        // Wer bei jeder Verspaetung um Minuten Alarm bekommt, schaltet den Kanal ab -- und
        // hoert dann auch den echten Ausfall nicht mehr.
        planExpecting(60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        lastSuccessfulRunAt(NOW.minus(Duration.ofMinutes(75)));

        watchdog.checkForMissedBackups();

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }

    @Test
    @DisplayName("Gemessen wird am letzten Erfolg, nicht am letzten Start")
    void measuresLastSuccessNotLastStart() {
        // Ein Plan, der stuendlich startet und stuendlich scheitert, hat seit Tagen nichts
        // gesichert -- auch wenn "zuletzt gelaufen" nach einer Minute aussieht.
        planExpecting(60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        lastSuccessfulRunAt(NOW.minus(Duration.ofDays(2)));

        watchdog.checkForMissedBackups();

        assertThat(published().severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("Ein Plan, der nie gelaufen ist, wird ab seiner Anlage gemessen")
    void planThatNeverRanIsMeasuredFromCreation() {
        // Genau der Fall, den dieser Waechter finden soll: eingerichtet und vergessen.
        planExpecting(24 * 60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(5)));
        Mockito.when(runs.findFirstByPlanIdAndStatusInOrderByFinishedAtDesc(
                Mockito.eq(PLAN_ID), Mockito.anyList())).thenReturn(Optional.empty());

        watchdog.checkForMissedBackups();

        assertThat(published().title()).contains("ausgeblieben");
    }

    @Test
    @DisplayName("Derselbe Vorfall wird nur einmal gemeldet")
    void reportsEachIncidentOnce() {
        // Wer alle fuenfzehn Minuten dieselbe Meldung bekommt, schaltet den Kanal ab.
        planExpecting(60, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        lastSuccessfulRunAt(NOW.minus(Duration.ofDays(2)));
        Mockito.when(notifications.alreadyPublished(Mockito.eq(MissedBackupWatchdog.EVENT_TYPE),
                Mockito.eq(PLAN_ID), Mockito.any())).thenReturn(true);

        watchdog.checkForMissedBackups();

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }

    @Test
    @DisplayName("Abgeschaltete Plaene und solche ohne erwartetes Intervall bleiben aussen vor")
    void ignoresDisabledAndUnmonitoredPlans() {
        planExpecting(null, true, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        watchdog.checkForMissedBackups();

        planExpecting(60, false, NotifyOn.FAILURE, NOW.minus(Duration.ofDays(30)));
        watchdog.checkForMissedBackups();

        planExpecting(60, true, NotifyOn.NEVER, NOW.minus(Duration.ofDays(30)));
        watchdog.checkForMissedBackups();

        Mockito.verify(notifications, Mockito.never()).publish(Mockito.any());
    }
}
