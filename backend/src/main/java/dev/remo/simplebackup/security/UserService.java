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
