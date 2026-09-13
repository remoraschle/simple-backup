package dev.remo.simplebackup.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Macht aus einer Anmeldung beim Anbieter einen Benutzer dieser Anwendung.
 *
 * <p>Der Anbieter sagt, <b>wer</b> jemand ist. Was er hier darf, entscheidet diese
 * Anwendung: Ein bekannter Benutzer behaelt seine Rolle, ein unbekannter bekommt Leserechte
 * -- es sei denn, es ist eine Administratorgruppe konfiguriert; dann ist der Anbieter fuer
 * die Rolle zustaendig und diese Anwendung folgt ihm.
 *
 * <p>Kein automatischer Administrator. Wer sich zum ersten Mal anmeldet, kann sonst allein
 * dadurch das Recht bekommen, saemtliche Sicherungen zu loeschen.
 */
class OidcLoginService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    private final UserService users;
    private final OidcProperties properties;

    /**
     * Holt die Angaben beim Anbieter.
     *
     * <p>Als Mitspieler und nicht als Oberklasse: So laesst sich die Abbildung auf einen
     * Benutzer dieser Anwendung pruefen, ohne dass ein Anbieter laufen muss -- und genau
     * diese Abbildung ist der Teil, in dem die Rechte entschieden werden.
     */
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    OidcLoginService(UserService users, OidcProperties properties) {
        this(users, properties, new OidcUserService());
    }

    OidcLoginService(UserService users, OidcProperties properties,
            OAuth2UserService<OidcUserRequest, OidcUser> delegate) {

        this.users = users;
        this.properties = properties;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser fromProvider = delegate.loadUser(request);

        String username = username(fromProvider);
        List<String> groups = groups(fromProvider);

        if (properties.requiredGroup() != null && !groups.contains(properties.requiredGroup())) {
            // Kein Hinweis darauf, welche Gruppe gefehlt haette: Die Meldung landet beim
            // Anmeldenden, und der soll aus einem Fehlschlag nichts ueber die Rechtestruktur
            // lernen. Im Log steht sie.
            log.info("Anmeldung von {} abgelehnt: nicht in der Gruppe {}", username,
                    properties.requiredGroup());
            throw new OAuth2AuthenticationException(new OAuth2Error("zugriff_verweigert",
                    "Dieses Konto ist für diese Anwendung nicht freigeschaltet", null));
        }

        AppUser user = users.signInFromProvider(username, roleFor(groups));

        return new DefaultOidcUser(authorities(user), request.getIdToken(), fromProvider.getUserInfo(),
                properties.usernameClaim());
    }

    /**
     * @return die Rolle, die der Anbieter vorgibt, oder {@code null}, wenn er dazu nichts sagt
     */
    private UserRole roleFor(List<String> groups) {
        if (properties.adminGroup() == null) {
            return null;
        }
        return groups.contains(properties.adminGroup()) ? UserRole.ADMIN : UserRole.VIEWER;
    }

    private String username(OidcUser user) {
        Object claim = user.getClaims().get(properties.usernameClaim());
        if (claim == null || claim.toString().isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("anspruch_fehlt",
                    "Der Anbieter liefert keinen Anspruch '%s', aus dem ein Anmeldename werden könnte"
                            .formatted(properties.usernameClaim()), null));
        }
        return claim.toString();
    }

    /** Gruppen kommen je nach Anbieter als Liste oder als einzelner Wert. */
    private List<String> groups(OidcUser user) {
        Map<String, Object> claims = user.getClaims();
        Object claim = claims.get(properties.groupsClaim());

        if (claim instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return claim == null ? List.of() : List.of(claim.toString());
    }

    /**
     * Ohne {@code PasswordChangeRequiredFilter.AUTHORITY}: Wer sich ueber einen Anbieter
     * anmeldet, hat hier kein Passwort, das er wechseln koennte.
     */
    private static List<GrantedAuthority> authorities(AppUser user) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
}
