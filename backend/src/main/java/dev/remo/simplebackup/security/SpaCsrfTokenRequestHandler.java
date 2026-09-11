package dev.remo.simplebackup.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Verbindet den BREACH-Schutz von Spring Security mit der Art, wie Angular CSRF-Tokens
 * verschickt.
 *
 * <p>Spring Security maskiert den Token im Cookie gegen BREACH. Angulars {@code HttpClient}
 * liest den Cookie {@code XSRF-TOKEN} aber unveraendert aus und schickt ihn als Header
 * {@code X-XSRF-TOKEN} -- ohne die Maskierung rueckgaengig zu machen. Deshalb wird der Wert
 * im Cookie maskiert gesetzt, beim Pruefen eines Headers aber unmaskiert erwartet.
 *
 * <p>Entspricht der von Spring Security fuer Single-Page-Anwendungen dokumentierten Loesung.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        // Erzwingt das Setzen des Cookies, damit die erste Anfrage der Anwendung ihn vorfindet.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                .resolveCsrfTokenValue(request, csrfToken);
    }
}
