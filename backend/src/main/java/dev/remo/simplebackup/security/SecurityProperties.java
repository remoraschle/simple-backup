package dev.remo.simplebackup.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxFailedLogins  Fehlversuche bis zur zeitweisen Sperre
 * @param lockoutDuration  Dauer der Sperre; sie laeuft von selbst ab, damit sich niemand
 *                         dauerhaft aus dem eigenen Werkzeug aussperrt
 */
@ConfigurationProperties(prefix = "simplebackup.security")
public record SecurityProperties(int maxFailedLogins, Duration lockoutDuration) {
}
