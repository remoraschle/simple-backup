/**
 * Alles, was restic betrifft: Repository-Adressen, Kommandos und die Auswertung der Ausgabe.
 *
 * <p>Als eigenes Modul deklariert, weil der Katalog die Aufbewahrungsregel und das Kommandobau
 * die Adressen braucht. Die Docker-Anbindung darunter bleibt dagegen bewusst modul-intern --
 * kein anderes Modul soll wissen, dass Schritte in Containern laufen.
 */
@org.springframework.modulith.ApplicationModule(displayName = "restic")
package dev.remo.simplebackup.engine.restic;
