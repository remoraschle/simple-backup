package dev.remo.simplebackup.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anmelden und Abmelden erledigt Spring Security selbst unter {@code /api/auth/login} und
 * {@code /api/auth/logout}. Hier stehen nur die beiden Dinge, die daneben gebraucht werden.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final UserService userService;

    AuthController(UserService userService) {
        this.userService = userService;
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
