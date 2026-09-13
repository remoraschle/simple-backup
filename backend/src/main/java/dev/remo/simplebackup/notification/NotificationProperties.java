package dev.remo.simplebackup.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dispatchInterval  wie oft der Postausgang geleert wird
 * @param batchSize         wie viele Meldungen je Durchgang zugestellt werden
 * @param pushoverEndpoint  abweichende Adresse fuer Pushover. Nur fuer Tests -- im Betrieb
 *                          bleibt das Feld leer und es gilt die echte Adresse.
 */
@ConfigurationProperties(prefix = "simplebackup.notification")
public record NotificationProperties(
        Duration dispatchInterval,
        int batchSize,
        String pushoverEndpoint) {

    public NotificationProperties {
        dispatchInterval = dispatchInterval == null ? Duration.ofSeconds(20) : dispatchInterval;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        // Nicht gesetzt heisst leer, nicht null: Die Umgebungsvariable ist in der
        // Konfiguration mit leerem Standardwert hinterlegt.
        pushoverEndpoint = pushoverEndpoint == null || pushoverEndpoint.isBlank() ? null : pushoverEndpoint;
    }
}
