package dev.remo.simplebackup.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Anmeldung ueber ein Session-Cookie, nicht ueber ein JWT im LocalStorage.
 *
 * <p>Ein JWT wuerde hier ein Problem loesen, das es nicht gibt: Es sind keine verteilten
 * Dienste im Spiel, die zustandslos Tokens pruefen muessten. Dafuer waere es per XSS
 * auslesbar und nicht widerrufbar. Ein {@code HttpOnly}-Cookie ist fuer diesen Zuschnitt
 * schlicht die sicherere und kleinere Loesung.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Argon2id, und zwar als delegierender Encoder: Vorhandene Hashes tragen ihr Praefix,
     * sodass sich das Verfahren spaeter wechseln laesst, ohne alle Passwoerter zu
     * invalidieren.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        String defaultId = "argon2";
        Map<String, PasswordEncoder> encoders = Map.of(
                defaultId, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        return new DelegatingPasswordEncoder(defaultId, encoders);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/session").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Prometheus-Metriken verraten Planbezeichnungen und Zeitpunkte.
                        .requestMatchers("/actuator/**").hasRole(UserRole.ADMIN.name())
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .hasRole(UserRole.ADMIN.name())
                        // Lesen darf auch VIEWER, alles Veraendernde bleibt beim ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/**")
                                .hasAnyRole(UserRole.ADMIN.name(), UserRole.VIEWER.name())
                        .requestMatchers("/api/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())

                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))

                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                writeJson(response, objectMapper, HttpStatus.OK,
                                        Map.of("username", authentication.getName())))
                        // Bewusst ohne Angabe, ob Benutzer oder Passwort falsch war.
                        .failureHandler((request, response, exception) ->
                                writeJson(response, objectMapper, HttpStatus.UNAUTHORIZED,
                                        Map.of("error", "Anmeldung fehlgeschlagen"))))

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("SIMPLEBACKUP_SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))

                // Ohne dies wuerde Spring bei fehlender Anmeldung auf eine Loginseite
                // umleiten; eine Single-Page-Anwendung braucht stattdessen ein klares 401.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        // Ein gestohlenes Cookie soll nicht beliebig viele Sitzungen erlauben.
                        .maximumSessions(5))

                // Nach der Rechtepruefung: Wer noch auf dem Erstpasswort sitzt, kommt nicht
                // an die API, auch nicht am Frontend vorbei.
                .addFilterAfter(new PasswordChangeRequiredFilter(objectMapper), AuthorizationFilter.class);

        return http.build();
    }

    /** Jackson 3 meldet Fehler als unchecked Exception; eine IOException kann hier nicht auftreten. */
    private static void writeJson(HttpServletResponse response, ObjectMapper objectMapper,
            HttpStatus status, Map<String, String> body) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
        response.getOutputStream().flush();
    }
}
