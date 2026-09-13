package dev.remo.simplebackup.notification;

/** Muss mit dem CHECK-Constraint auf {@code notification_channel.type} uebereinstimmen. */
public enum ChannelType {
    /** Push auf Telefon und Uhr, mit Quittierungspflicht bei hoechster Stufe. */
    PUSHOVER,
    /** Ein beliebiger Endpunkt, der JSON entgegennimmt -- Gotify, ntfy, Discord, Eigenbau. */
    WEBHOOK
}
