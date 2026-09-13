package dev.remo.simplebackup.notification;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
        @JsonSubTypes.Type(value = ChannelConfig.Webhook.class, name = "WEBHOOK"),
        @JsonSubTypes.Type(value = ChannelConfig.Smtp.class, name = "SMTP")})
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

    /**
     * E-Mail ueber einen eigenen Mailserver.
     *
     * @param host         Mailserver
     * @param port         587 fuer STARTTLS, 465 fuer durchgehendes TLS, 25 ohne
     * @param startTls     ob die Verbindung nach dem Verbinden verschluesselt wird. Bei
     *                     Port 465 ist sie es von Anfang an; das wird am Port erkannt.
     * @param from         Absender
     * @param recipients   Empfaenger, mindestens einer
     * @param username     Anmeldename, oder {@code null} fuer einen Server ohne Anmeldung
     * @param credentialId Verweis auf das Passwort in der verschluesselten Ablage. Ein
     *                     Passwort gehoert nicht in die Konfiguration, denn die liefert die
     *                     API im Klartext aus.
     */
    record Smtp(
            @NotBlank String host,
            int port,
            boolean startTls,
            @NotBlank String from,
            List<String> recipients,
            String username,
            UUID credentialId) implements ChannelConfig {

        public Smtp {
            port = port <= 0 ? 587 : port;
            recipients = recipients == null ? List.of() : List.copyOf(recipients);

            if (recipients.isEmpty()) {
                throw new IllegalArgumentException("Ohne Empfaenger geht keine Meldung hinaus");
            }
            for (String recipient : recipients) {
                requireAddress(recipient, "Empfaenger");
            }
            requireAddress(from, "Absender");

            if (username != null && username.isBlank()) {
                username = null;
            }
            if (username != null && credentialId == null) {
                throw new IllegalArgumentException("Zu einem Anmeldenamen gehoert auch ein Passwort");
            }
        }

        /**
         * Eine Adresse ohne {@code @} ist keine.
         *
         * <p>Bewusst keine vollstaendige Pruefung nach RFC: Die trifft entweder zu viel
         * oder zu wenig, und ob der Server die Adresse annimmt, sagt am Ende nur der
         * Server. Diese Pruefung faengt den Tippfehler ab, nicht den Sonderfall.
         */
        private static void requireAddress(String value, String was) {
            if (value == null || !value.contains("@") || value.contains(" ")) {
                throw new IllegalArgumentException("%s ist keine E-Mail-Adresse: %s".formatted(was, value));
            }
        }

        @Override
        public ChannelType type() {
            return ChannelType.SMTP;
        }
    }
}
