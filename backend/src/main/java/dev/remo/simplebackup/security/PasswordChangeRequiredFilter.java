package dev.remo.simplebackup.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Sperrt die API, solange das Erstpasswort noch gilt.
 *
 * <p>Der Zwang zum Wechsel steckte bisher allein im Frontend -- und ein Frontend ist keine
 * Sicherheitsgrenze. Wer das beim Erststart einmalig protokollierte Passwort kannte, konnte
 * die gesamte API benutzen, ohne es je zu wechseln: Zugangsdaten anlegen, Ziele umbiegen,
 * Laeufe starten. Genau das verhindert dieser Filter.
 *
 * <p>Erlaubt bleibt nur, was zum Wechsel selbst noetig ist. Die Markierung kommt als
 * Berechtigung aus {@link AppUserDetailsService} und damit ohne zusaetzliche Abfrage je
 * Anfrage; nach dem Wechsel wird die Sitzung ohnehin verworfen, sodass die naechste
 * Anmeldung die Markierung nicht mehr traegt.
 */
class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** Traegt ein Benutzer diese Berechtigung, muss er zuerst sein Passwort wechseln. */
    static final String AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

    private static final Set<String> ALLOWED =
            Set.of("/api/auth/login", "/api/auth/logout", "/api/auth/password", "/api/auth/session");

    private final ObjectMapper objectMapper;

    PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return !path.startsWith("/api/") || ALLOWED.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, jakarta.servlet.ServletException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated() && authentication.getAuthorities()
                .stream().anyMatch(granted -> AUTHORITY.equals(granted.getAuthority()))) {

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Das Erstpasswort muss zuerst geändert werden.");
            problem.setTitle("Passwortwechsel erforderlich");

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            response.getOutputStream().flush();
            return;
        }

        chain.doFilter(request, response);
    }

    /** Ein gesetzter Kontextpfad darf den Vergleich nicht ins Leere laufen lassen. */
    private static String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
