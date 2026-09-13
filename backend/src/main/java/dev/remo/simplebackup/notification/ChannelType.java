package dev.remo.simplebackup.notification;

/** Muss mit dem CHECK-Constraint auf {@code notification_channel.type} uebereinstimmen. */
public enum ChannelType {
    /** Push auf Telefon und Uhr, mit Quittierungspflicht bei hoechster Stufe. */
    PUSHOVER,
    /** Ein beliebiger Endpunkt, der JSON entgegennimmt -- Gotify, ntfy, Discord, Eigenbau. */
    WEBHOOK,
    /**
     * E-Mail ueber einen eigenen Mailserver.
     *
     * <p>Der Kanal, den jeder hat, auch ohne Konto bei einem Dienst -- und der einzige, der
     * auch dann noch ankommt, wenn das Telefon gewechselt wurde. Fuer den Alarm mitten in
     * der Nacht ist er die schlechtere Wahl als Pushover; als zweiter Weg daneben ist er
     * die richtige.
     */
    SMTP
}
