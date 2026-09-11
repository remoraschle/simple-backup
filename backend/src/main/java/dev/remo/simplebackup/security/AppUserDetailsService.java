package dev.remo.simplebackup.security;

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

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(AuthorityUtils.createAuthorityList("ROLE_" + user.getRole().name()))
                .disabled(!user.isEnabled())
                .accountLocked(user.isLocked())
                .build();
    }
}
