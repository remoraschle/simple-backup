package dev.remo.simplebackup.catalog;

import dev.remo.simplebackup.shared.RetentionRule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Alles, was zur Ausfuehrung eines Plans gebraucht wird -- als Wertobjekt statt als Entitaet.
 *
 * <p>Damit kommt die Ausfuehrung ohne Datenbankzugriff und ohne offene Transaktion aus. Ein
 * Backup laeuft Stunden; eine Entitaet so lange an einer Transaktion haengen zu lassen, waere
 * die schlechtere Wahl.
 *
 * @param resticHost Kennung, unter der restic die Snapshots ablegt. Muss stabil bleiben.
 * @param resticTag  Kennzeichnung; begrenzt zugleich, was {@code forget} loeschen darf.
 */
public record ExecutablePlan(
        UUID planId,
        String planName,
        String resticHost,
        String resticTag,
        SourceConfig source,
        List<ExecutableTarget> targets,
        Duration timeout,
        RetentionRule retention) {

    public ExecutablePlan {
        targets = List.copyOf(targets);
    }

    /** Nur die Ziele, auf die tatsaechlich geschrieben wird. */
    public List<ExecutableTarget> enabledTargets() {
        return targets.stream().filter(ExecutableTarget::enabled).toList();
    }
}
