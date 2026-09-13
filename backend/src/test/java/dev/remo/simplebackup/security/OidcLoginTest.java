package dev.remo.simplebackup.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.remo.simplebackup.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Anmeldung ueber einen Anbieter.
 *
 * <p>Der Anbieter sagt, wer jemand ist. Was er hier darf, entscheidet diese Anwendung --
 * und genau daran wird hier gemessen: Niemand wird durch eine blosse Anmeldung
 * Administrator, ein hier abgeschaltetes Konto kommt auch ueber diesen Weg nicht herein,
 * und ohne die verlangte Gruppe geht gar nichts.
 *
 * <p>Ohne laufenden Anbieter: Geprueft wird die Abbildung auf einen Benutzer, nicht das
 * Protokoll. Das Protokoll spricht Spring Security, die Rechte vergibt diese Anwendung.
 */
class OidcLoginTest extends IntegrationTestBase {

    @Autowired
    private UserService users;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String username;

    @BeforeEach
    void setUp() {
        username = "oidc-" + UUID.randomUUID();
    }

    private OidcLoginService serviceWith(OidcProperties properties, Map<String, Object> given) {
        Map<String, Object> all = claims(given);
        return new OidcLoginService(users, properties, request -> oidcUser(all));
    }

    private static OidcProperties properties(String adminGroup, String requiredGroup) {
        return new OidcProperties(null, null, "preferred_username", "groups", adminGroup, requiredGroup);
    }

    /**
     * Die Kennung {@code sub} traegt hier den Namen des Prinzipals -- nicht der Anspruch,
     * um den es geht. Sonst liesse sich der Fall "Anspruch fehlt" gar nicht nachstellen.
     */
    private static OidcUser oidcUser(Map<String, Object> claims) {
        return new DefaultOidcUser(List.of(), idToken(claims), "sub");
    }

    /** Jeder Anbieter liefert {@code sub}; die Tests ergaenzen es deshalb stillschweigend. */
    private static Map<String, Object> claims(Map<String, Object> given) {
        var all = new java.util.LinkedHashMap<String, Object>(given);
        all.putIfAbsent("sub", "subjekt-1");
        return all;
    }

    private static OidcIdToken idToken(Map<String, Object> claims) {
        return new OidcIdToken("token-wert", Instant.now(), Instant.now().plusSeconds(300), claims);
    }

    /** Die Anfrage traegt nur das Token weiter; der Anbieter wird hier nicht befragt. */
    private static OidcUserRequest request(Map<String, Object> given) {
        Map<String, Object> claims = claims(given);

        var registration = ClientRegistration.withRegistrationId("oidc")
                .clientId("simple-backup")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/oidc")
                .authorizationUri("https://anbieter.invalid/authorize")
                .tokenUri("https://anbieter.invalid/token")
                .userInfoUri("https://anbieter.invalid/userinfo")
                .jwkSetUri("https://anbieter.invalid/jwks")
                .userNameAttributeName("preferred_username")
                .scope("openid")
                .build();

        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "zugriff",
                Instant.now(), Instant.now().plusSeconds(300));

        return new OidcUserRequest(registration, token, idToken(claims));
    }

    private AppUser stored() {
        return repository.findByUsername(username).orElseThrow();
    }

    @Nested
    @DisplayName("Erste Anmeldung")
    class FirstLogin {

        @Test
        @DisplayName("Ein unbekannter Benutzer wird angelegt -- mit Leserechten")
        void createsViewerByDefault() {
            // Der wichtigste Satz dieser Datei: Eine blosse Anmeldung macht niemanden zum
            // Administrator. Wer loeschen darf, wird hier bestimmt, nicht beim Anbieter.
            Map<String, Object> claims = Map.of("preferred_username", username);

            OidcUser user = serviceWith(properties(null, null), claims).loadUser(request(claims));

            assertThat(user.getName()).isEqualTo(username);
            assertThat(stored().getRole()).isEqualTo(UserRole.VIEWER);
            assertThat(user.getAuthorities()).extracting(Object::toString)
                    .containsExactly("ROLE_VIEWER");
        }

        @Test
        @DisplayName("Mit Administratorgruppe entscheidet der Anbieter")
        void honoursAdminGroup() {
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", List.of("backup-admins", "irgendwas"));

            serviceWith(properties("backup-admins", null), claims).loadUser(request(claims));

            assertThat(stored().getRole()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("Wer nicht in der Administratorgruppe ist, darf nur lesen")
        void othersRemainViewers() {
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", List.of("hausmeister"));

            serviceWith(properties("backup-admins", null), claims).loadUser(request(claims));

            assertThat(stored().getRole()).isEqualTo(UserRole.VIEWER);
        }

        @Test
        @DisplayName("Ein Anbieter ohne den erwarteten Anspruch wird abgewiesen")
        void rejectsMissingUsernameClaim() {
            // Sonst entstuende ein Benutzer namens "null" -- oder schlimmer, alle
            // Anmeldungen liefen auf denselben Benutzer zusammen.
            Map<String, Object> claims = Map.of("kein_name", "x");

            assertThatThrownBy(() -> serviceWith(properties(null, null), claims)
                    .loadUser(request(claims)))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .hasMessageContaining("preferred_username");
        }
    }

    @Nested
    @DisplayName("Zugang und Sperre")
    class Access {

        @Test
        @DisplayName("Ohne die verlangte Gruppe kommt niemand herein")
        void requiresGroup() {
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", List.of("gaeste"));

            assertThatThrownBy(() -> serviceWith(properties(null, "backup-nutzer"), claims)
                    .loadUser(request(claims)))
                    .isInstanceOf(OAuth2AuthenticationException.class);

            // Und es entsteht auch kein Benutzer nebenbei.
            assertThat(repository.findByUsername(username)).isEmpty();
        }

        @Test
        @DisplayName("Die Ablehnung verraet nicht, welche Gruppe gefehlt haette")
        void keepsTheReasonToItself() {
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", List.of("gaeste"));

            assertThatThrownBy(() -> serviceWith(properties(null, "backup-nutzer"), claims)
                    .loadUser(request(claims)))
                    .hasMessageNotContaining("backup-nutzer");
        }

        @Test
        @DisplayName("Eine Gruppe als einzelner Wert zaehlt genauso")
        void acceptsSingleValuedGroupClaim() {
            // Nicht jeder Anbieter liefert Gruppen als Liste.
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", "backup-admins");

            serviceWith(properties("backup-admins", null), claims).loadUser(request(claims));

            assertThat(stored().getRole()).isEqualTo(UserRole.ADMIN);
        }
    }

    @Nested
    @DisplayName("Wiederkehrende Anmeldung")
    class ReturningUser {

        @Test
        @DisplayName("Ohne Gruppenabbildung bleibt die hier vergebene Rolle bestehen")
        void keepsLocalRoleWithoutMapping() {
            users.create(username, "ein-langes-testpasswort", UserRole.ADMIN, false);
            Map<String, Object> claims = Map.of("preferred_username", username);

            serviceWith(properties(null, null), claims).loadUser(request(claims));

            assertThat(stored().getRole()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("Mit Gruppenabbildung folgt die Rolle dem Anbieter -- auch nach unten")
        void followsProviderDownwards() {
            // Wem dort die Gruppe entzogen wurde, der darf hier nicht Administrator bleiben.
            users.create(username, "ein-langes-testpasswort", UserRole.ADMIN, false);
            Map<String, Object> claims = Map.of("preferred_username", username,
                    "groups", List.of("hausmeister"));

            serviceWith(properties("backup-admins", null), claims).loadUser(request(claims));

            assertThat(stored().getRole()).isEqualTo(UserRole.VIEWER);
        }

        @Test
        @DisplayName("Ein hier abgeschaltetes Konto kommt auch ueber den Anbieter nicht herein")
        void rejectsDisabledAccount() {
            users.create(username, "ein-langes-testpasswort", UserRole.VIEWER, false);
            // So, wie ein Betreiber es taete: Es gibt keine Oberflaeche zum Abschalten eines
            // Kontos, wohl aber das Kennzeichen in der Datenbank.
            jdbc.update("UPDATE app_user SET enabled = false WHERE username = ?", username);

            Map<String, Object> claims = Map.of("preferred_username", username);

            assertThatThrownBy(() -> serviceWith(properties(null, null), claims)
                    .loadUser(request(claims)))
                    .isInstanceOf(DisabledException.class);
        }

        @Test
        @DisplayName("Die Anmeldung wird vermerkt")
        void recordsTheLogin() {
            Map<String, Object> claims = Map.of("preferred_username", username);

            serviceWith(properties(null, null), claims).loadUser(request(claims));

            assertThat(stored().getLastLoginAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Ohne konfigurierten Anbieter")
    class WithoutProvider {

        @Autowired(required = false)
        private ClientRegistrationRepository registrations;

        @Test
        @DisplayName("Gibt es den Anmeldeweg gar nicht")
        void isCompletelyAbsent() {
            // Ein Anmeldeknopf, der ins Leere fuehrt, waere schlechter als keiner.
            assertThat(registrations).isNull();
        }
    }
}
