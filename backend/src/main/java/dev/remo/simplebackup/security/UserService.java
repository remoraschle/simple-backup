package dev.remo.simplebackup.security;

import dev.remo.simplebackup.shared.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    UserService(AppUserRepository repository, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public SessionInfo describe(String username) {
        AppUser user = require(username);
        return new SessionInfo(true, user.getUsername(), user.getRole(), user.isMustChangePassword());
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        AppUser user = require(username);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Das aktuelle Passwort ist falsch");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Das neue Passwort muss sich vom bisherigen unterscheiden");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        repository.save(user);
        auditService.record(username, "PASSWORD_CHANGED", "app_user", user.getId().toString(), null);
    }

    /**
     * Meldet einen Benutzer an, der von einem Anbieter kommt.
     *
     * <p>Wer noch nicht bekannt ist, wird angelegt -- sonst muesste jemand jeden Benutzer
     * vorher von Hand eintragen, und das ist genau die Arbeit, die ein Anbieter abnehmen
     * soll.
     *
     * @param roleFromProvider Rolle laut Anbieter, oder {@code null}, wenn er dazu nichts
     *                         sagt. Dann bekommt ein neuer Benutzer Leserechte und ein
     *                         bekannter behaelt, was er hat: Eine Anmeldung darf niemanden
     *                         versehentlich zum Administrator machen.
     */
    AppUser signInFromProvider(String username, UserRole roleFromProvider) {
        AppUser user = repository.findByUsername(username).orElse(null);

        if (user == null) {
            user = new AppUser(username, unusablePassword(),
                    roleFromProvider == null ? UserRole.VIEWER : roleFromProvider, false);

            auditService.record(username, "USER_CREATED_FROM_PROVIDER", "app_user", null,
                    "{\"role\":\"%s\"}".formatted(user.getRole()));

        } else if (roleFromProvider != null && user.getRole() != roleFromProvider) {
            auditService.record(username, "ROLE_CHANGED_BY_PROVIDER", "app_user",
                    user.getId().toString(),
                    "{\"from\":\"%s\",\"to\":\"%s\"}".formatted(user.getRole(), roleFromProvider));

            user.changeRole(roleFromProvider);
        }

        if (!user.isEnabled()) {
            // Der Anbieter kennt die Sperre hier nicht. Ohne diese Pruefung kaeme ein
            // abgeschalteter Benutzer ueber den zweiten Weg doch wieder herein.
            throw new org.springframework.security.authentication.DisabledException(
                    "Dieses Konto ist in der Anwendung abgeschaltet");
        }

        user.recordSuccessfulLogin();
        return repository.save(user);
    }

    /**
     * Ein Hash, zu dem es kein Passwort gibt.
     *
     * <p>Ein Benutzer vom Anbieter hat hier keines. Das Feld bleibt trotzdem gefuellt, und
     * zwar mit dem Hash eines Zufallswerts, den niemand kennt -- so scheitert eine
     * Anmeldung ueber das Formular an derselben Pruefung wie jedes falsche Passwort, statt
     * an einem Sonderfall im Anmeldeweg.
     */
    private String unusablePassword() {
        return passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    AppUser create(String username, String rawPassword, UserRole role, boolean mustChangePassword) {
        AppUser user = new AppUser(username, passwordEncoder.encode(rawPassword), role, mustChangePassword);
        return repository.save(user);
    }

    boolean noUsersExist() {
        return repository.count() == 0;
    }

    private AppUser require(String username) {
        return repository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("Benutzer nicht gefunden"));
    }
}
