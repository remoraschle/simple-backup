package dev.remo.simplebackup.notification;

/**
 * Ein Weg, eine Meldung loszuwerden.
 *
 * <p>Wirft bei jedem Misserfolg, statt einen Wahrheitswert zurueckzugeben: Der Grund
 * gehoert in den Postausgang, damit man spaeter sieht, warum nichts ankam.
 */
interface NotificationTransport {

    ChannelType type();

    /**
     * Stellt zu.
     *
     * @return eine Quittung des Dienstes, sofern es eine gibt -- bei Pushover laesst sich
     *         damit spaeter abfragen, ob ein Alarm bestaetigt wurde
     * @throws NotificationException wenn die Zustellung gescheitert ist
     */
    String send(ChannelConfig config, OutboxEntry entry);
}
