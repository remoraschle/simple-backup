package dev.remo.simplebackup.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Wie eine Anmeldung ueber einen Anbieter ablaeuft.
 *
 * <p>Eingehaengt wird das nur, wenn es ein {@link ClientRegistrationRepository} gibt; diese
 * Entscheidung trifft die Sicherheitskonfiguration. Bewusst keine Bohne vom Typ
 * {@code Customizer<OAuth2LoginConfigurer<…>>}: Solche Bohnen wendet Spring Security von
 * sich aus auf die Standard-{@code HttpSecurity} an -- und dann scheiterte der Start jeder
 * Installation ohne Anbieter an genau der Registrierung, die es dort nicht gibt.
 */
@Component
class OidcLoginSupport {

    private final UserService users;
    private final OidcProperties properties;

    OidcLoginSupport(UserService users, OidcProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    /**
     * Haengt den Anmeldeweg ein.
     *
     * <p>Nach der Anmeldung geht es immer auf die Startseite und nie auf die zuletzt
     * angefragte Adresse: Die Oberflaeche ist eine Single-Page-Anwendung, die ihren Zustand
     * selbst wiederfindet -- und eine gespeicherte Anfrage waere hier haeufiger ein
     * API-Aufruf als eine Seite.
     */
    void applyTo(HttpSecurity http) throws Exception {
        http.oauth2Login(login -> login
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(
                        new OidcLoginService(users, properties)))
                .successHandler(new SimpleUrlAuthenticationSuccessHandler("/"))
                .failureHandler(new ExplainingFailureHandler()));
    }

    /**
     * Schickt den Browser mit einer Begruendung zur Anmeldeseite zurueck.
     *
     * <p>Ein wortloser Sprung dorthin sieht aus wie ein Fehler der Anwendung -- dabei ist
     * die haeufigste Ursache eine fehlende Gruppe beim Anbieter, und das kann man sagen.
     */
    private static final class ExplainingFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException exception) throws IOException, ServletException {

            setDefaultFailureUrl(UriComponentsBuilder.fromPath("/login")
                    .queryParam("fehler", exception.getMessage())
                    .build().encode().toUriString());

            super.onAuthenticationFailure(request, response, exception);
        }
    }
}
