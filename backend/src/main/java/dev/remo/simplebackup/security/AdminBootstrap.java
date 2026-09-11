package dev.remo.simplebackup.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Legt beim allerersten Start ein Administratorkonto an.
 *
 * <p>Das Passwort wird zufaellig erzeugt und einmalig ins Log geschrieben, statt ein
 * bekanntes Standardpasswort zu setzen. Ein Werkzeug, das mit {@code admin/admin} ans Netz
 * geht, ist ab der ersten Minute angreifbar -- und niemand aendert es nachtraeglich.
 *
 * <p>Beim ersten Anmelden ist ein Passwortwechsel erzwungen.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String DEFAULT_USERNAME = "admin";

    private final UserService userService;

    AdminBootstrap(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userService.noUsersExist()) {
            return;
        }

        String password = generatePassword();
        userService.create(DEFAULT_USERNAME, password, UserRole.ADMIN, true);

        log.warn("""

                ════════════════════════════════════════════════════════════════
                  Erststart: Administratorkonto angelegt

                    Benutzer:  {}
                    Passwort:  {}

                  Dieses Passwort erscheint nur dieses eine Mal. Beim ersten
                  Anmelden muss es geaendert werden.
                ════════════════════════════════════════════════════════════════
                """, DEFAULT_USERNAME, password);
    }

    private static String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
