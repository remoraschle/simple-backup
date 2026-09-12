/**
 * Alles, was restic betrifft: Repository-Adressen, Kommandos und die Auswertung der Ausgabe.
 *
 * <p>Ein eigenes Modul und kein Unterpaket der Ausfuehrung: restic-Wissen ist ein eigener
 * Belang, unabhaengig davon, ob ein Kommando in einem Container oder als Kindprozess laeuft.
 * Umgekehrt soll die Ausfuehrung nichts ueber restic wissen muessen -- sie fuehrt
 * Argumentlisten aus, gleich welcher Herkunft.
 */
@org.springframework.modulith.ApplicationModule(displayName = "restic")
package dev.remo.simplebackup.restic;
