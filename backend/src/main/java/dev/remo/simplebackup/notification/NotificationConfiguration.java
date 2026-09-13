package dev.remo.simplebackup.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotificationConfiguration {

    /**
     * Der HTTP-Client fuer den Versand.
     *
     * <p>Eigene Bean statt eines Clients je Kanal: Der Verbindungspool soll geteilt werden.
     * Weiterleitungen werden bewusst nur fuer normale Ziele gefolgt, und die Verbindung hat
     * ein knappes Zeitlimit -- ein haengender Alarmkanal darf den Postausgang nicht
     * blockieren.
     */
    @Bean
    HttpClient notificationHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
