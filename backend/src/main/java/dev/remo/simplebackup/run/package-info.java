/**
 * Ausfuehrung und Historie der Backup-Laeufe.
 *
 * <p>Ein Lauf zerfaellt in Schritte. Erst dadurch ist {@code PARTIAL} darstellbar:
 * Beschaffung erfolgreich, Uebertragung auf Ziel A erfolgreich, auf Ziel B gescheitert. Ein
 * Lauf, der nur einen Gesamtzustand kennt, muesste diesen Fall entweder als Erfolg oder als
 * Fehlschlag ausgeben -- beides waere falsch.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Laeufe")
package dev.remo.simplebackup.run;
