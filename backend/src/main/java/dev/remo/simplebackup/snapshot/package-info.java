/**
 * Snapshots: was gesichert wurde, und wie man es zurueckbekommt.
 *
 * <p>Ein eigenes Modul, weil es hier um das Archiv geht und nicht um den Lauf, der es
 * gefuellt hat. Ein Lauf ist ein Ereignis von gestern; ein Snapshot ist der Bestand, auf den
 * es im Ernstfall ankommt -- durchsuchbar, wiederherstellbar, pruefbar.
 *
 * <p>Hier liegt auch das Wissen, wie ein Ziel als restic-Repository anzusprechen ist. Die
 * Ausfuehrung von Laeufen greift darauf zurueck: Wer schreibt und wer liest, soll dieselbe
 * Adresse und dieselben Zugangsdaten verwenden.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Snapshots")
package dev.remo.simplebackup.snapshot;
