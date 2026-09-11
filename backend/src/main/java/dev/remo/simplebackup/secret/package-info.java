/**
 * Verschluesselte Ablage von Zugangsdaten.
 *
 * <p>Zu schuetzen sind GitHub-Tokens, S3-Schluessel, SSH-Keys, Datenbankpasswoerter,
 * restic-Repository-Passwoerter und Pushover-Zugaenge. Klartext verlaesst dieses Modul
 * nur ueber {@code CredentialService#reveal}, und diese Methode wird ausschliesslich
 * beim Bestuecken eines Runner-Containers aufgerufen, nie fuer die API.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Zugangsdaten")
package dev.remo.simplebackup.secret;
