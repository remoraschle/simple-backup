package dev.remo.simplebackup.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;

    AppUserDetailsService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer"));

        // Der Zwang zum Passwortwechsel haengt an der Anmeldung, nicht an jeder Anfrage:
        // Als Berechtigung mitgegeben, kommt der Filter ohne eigene Abfrage aus.
        List<String> authorities = new ArrayList<>(List.of("ROLE_" + user.getRole().name()));
        if (user.isMustChangePassword()) {
            authorities.add(PasswordChangeRequiredFilter.AUTHORITY);
        }

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(AuthorityUtils.createAuthorityList(authorities.toArray(String[]::new)))
                .disabled(!user.isEnabled())
                .accountLocked(user.isLocked())
                .build();
    }
}
