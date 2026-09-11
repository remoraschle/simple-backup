/**
 * Ausfuehrung der eigentlichen Backup-Werkzeuge.
 *
 * <p>Das Backend fuehrt {@code restic}, {@code rsync} und {@code pg_dump} nicht selbst aus,
 * sondern startet je Schritt einen kurzlebigen Container. Dieses Modul kapselt das hinter
 * {@link dev.remo.simplebackup.engine.BackupExecutor}, damit der Rest der Anwendung nichts
 * von Containern wissen muss -- und damit Tests ohne Docker-Daemon auskommen.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ausfuehrung")
package dev.remo.simplebackup.engine;
