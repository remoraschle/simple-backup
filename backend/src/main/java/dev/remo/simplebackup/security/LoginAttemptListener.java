package dev.remo.simplebackup.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bremst Rateversuche, indem ein Konto nach mehreren Fehlversuchen zeitweise gesperrt wird.
 *
 * <p>Ueber Spring-Security-Ereignisse statt ueber eigene Handler: Das greift unabhaengig
 * davon, auf welchem Weg sich jemand anmeldet, und haelt die Zaehllogik aus dem
 * Anmeldevorgang heraus.
 *
 * <p>Die Sperre laeuft von selbst ab. Eine dauerhafte Sperre wuerde bedeuten, dass man sich
 * aus dem eigenen Werkzeug aussperren kann -- ausgerechnet aus dem, das im Notfall die
 * Daten zurueckholt.
 */
@Component
class LoginAttemptListener {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptListener.class);

    private final AppUserRepository repository;
    private final SecurityProperties properties;

    LoginAttemptListener(AppUserRepository repository, SecurityProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @EventListener
    @Transactional
    void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = String.valueOf(event.getAuthentication().getName());
        repository.findByUsername(username).ifPresent(user -> {
            user.recordFailedLogin(properties.maxFailedLogins(), properties.lockoutDuration());
            repository.save(user);
            if (user.isLocked()) {
                log.warn("Konto '{}' nach {} Fehlversuchen bis {} gesperrt",
                        username, properties.maxFailedLogins(), user.getLockedUntil());
            }
        });
        // Unbekannte Benutzernamen werden bewusst nicht protokolliert: Das wuerde eine
        // Liste gueltiger Namen in die Logs schreiben.
    }

    @EventListener
    @Transactional
    void onSuccess(AuthenticationSuccessEvent event) {
        repository.findByUsername(event.getAuthentication().getName()).ifPresent(user -> {
            user.recordSuccessfulLogin();
            repository.save(user);
        });
    }
}
