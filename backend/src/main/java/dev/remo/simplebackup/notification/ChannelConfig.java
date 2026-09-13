package dev.remo.simplebackup.notification;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Typisierte Konfiguration eines Kanals.
 *
 * <p>Wie bei Quellen und Zielen stehen Zugangsdaten nur als Verweis auf die verschluesselte
 * Ablage. Diese Konfiguration liegt als JSONB in der Datenbank und geht unveraendert an die
 * API -- sie darf deshalb nichts Geheimes enthalten.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChannelConfig.Pushover.class, name = "PUSHOVER"),
        @JsonSubTypes.Type(value = ChannelConfig.Webhook.class, name = "WEBHOOK")})
public sealed interface ChannelConfig {

    ChannelType type();

    /**
     * Pushover.
     *
     * @param credentialId Verweis auf einen Zugang vom Typ {@code PUSHOVER}; darin stehen
     *                     Anwendungs-Token und Benutzerschluessel gemeinsam
     * @param device       nur an dieses Geraet, oder {@code null} fuer alle
     * @param emergency    ob kritische Meldungen mit Quittierungspflicht gesendet werden.
     *                     Genau dafuer ist Pushover hier ausgewaehlt worden: Eine Meldung,
     *                     die man verschlafen kann, nuetzt beim gescheiterten Backup wenig.
     * @param retrySeconds Abstand der Wiederholungen bei Quittierungspflicht
     * @param expireSeconds wie lange Pushover insgesamt wiederholt
     */
    record Pushover(
            UUID credentialId,
            String device,
            boolean emergency,
            Integer retrySeconds,
            Integer expireSeconds) implements ChannelConfig {

        public Pushover {
            if (credentialId == null) {
                throw new IllegalArgumentException("Ohne hinterlegten Pushover-Zugang geht nichts");
            }
            retrySeconds = clamp(retrySeconds, 60);
            expireSeconds = clamp(expireSeconds, 3600);
        }

        /**
         * Die Grenzen stammen von Pushover selbst: haeufiger als alle 30 Sekunden wiederholt
         * der Dienst nicht, und laenger als drei Stunden gar nicht.
         */
        private static Integer clamp(Integer value, int fallback) {
            return value == null ? fallback : Math.clamp(value, 30, 10800);
        }

        @Override
        public ChannelType type() {
            return ChannelType.PUSHOVER;
        }
    }

    /**
     * Ein beliebiger Endpunkt, der JSON entgegennimmt.
     *
     * @param url          Adresse, http oder https
     * @param headerName   Name eines zusaetzlichen Kopffeldes, etwa {@code Authorization}
     * @param credentialId Verweis auf den Wert dieses Kopffeldes in der verschluesselten
     *                     Ablage. Ein Token gehoert nicht in die Konfiguration, denn die
     *                     liefert die API im Klartext aus.
     */
    record Webhook(
            @NotBlank String url,
            String headerName,
            UUID credentialId) implements ChannelConfig {

        public Webhook {
            if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                throw new IllegalArgumentException(
                        "Die Adresse muss mit http:// oder https:// beginnen: " + url);
            }
            if (headerName != null && headerName.isBlank()) {
                headerName = null;
            }
            if (headerName == null && credentialId != null) {
                throw new IllegalArgumentException(
                        "Zu einem hinterlegten Wert gehoert auch der Name des Kopffeldes");
            }
        }

        @Override
        public ChannelType type() {
            return ChannelType.WEBHOOK;
        }
    }
}
