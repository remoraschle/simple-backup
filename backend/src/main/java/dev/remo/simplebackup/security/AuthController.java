package dev.remo.simplebackup.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anmelden und Abmelden erledigt Spring Security selbst unter {@code /api/auth/login} und
 * {@code /api/auth/logout}. Hier steht nur, was daneben gebraucht wird: welche Anmeldewege
 * es gibt, ob noch eine Sitzung besteht, und der Passwortwechsel.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final UserService userService;
    private final ObjectProvider<OidcProperties> oidcProperties;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    AuthController(UserService userService, ObjectProvider<OidcProperties> oidcProperties,
            ObjectProvider<ClientRegistrationRepository> registrations) {

        this.userService = userService;
        this.oidcProperties = oidcProperties;
        this.registrations = registrations;
    }

    /**
     * Welche Anmeldewege es gibt.
     *
     * <p>Oeffentlich erreichbar, weil die Anmeldeseite es wissen muss, bevor irgendjemand
     * angemeldet ist. Verraten wird nur, ob ein Anbieter eingerichtet ist und wie der Knopf
     * heissen soll -- das sieht ohnehin jeder, der die Seite aufruft.
     */
    @GetMapping("/providers")
    LoginProviders providers() {
        OidcProperties oidc = oidcProperties.getIfAvailable();

        if (oidc == null || registrations.getIfAvailable() == null) {
            return new LoginProviders(false, null, null);
        }
        return new LoginProviders(true, oidc.displayName(), oidc.authorizationUrl());
    }

    /** @param authorizationUrl Adresse, auf die der Knopf zeigt, oder {@code null} */
    record LoginProviders(boolean oidcEnabled, String displayName, String authorizationUrl) {
    }

    /**
     * Erlaubt dem Frontend, beim Start zu ermitteln, ob noch eine Sitzung besteht -- ohne
     * dafuer einen 401 provozieren zu muessen. Deshalb oeffentlich erreichbar.
     */
    @GetMapping("/session")
    SessionInfo session(Principal principal) {
        return principal == null ? SessionInfo.anonymous() : userService.describe(principal.getName());
    }

    /**
     * Nach erfolgreichem Wechsel wird die Sitzung beendet: Wer sein Passwort aendert, will
     * in der Regel gerade, dass bestehende Zugaenge ungueltig werden.
     */
    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            Principal principal, HttpServletRequest httpRequest) {

        userService.changePassword(principal.getName(), request.currentPassword(), request.newPassword());
        httpRequest.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }
}
