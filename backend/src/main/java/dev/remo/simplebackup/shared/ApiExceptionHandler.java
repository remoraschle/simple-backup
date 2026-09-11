package dev.remo.simplebackup.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Uebersetzt Ausnahmen in RFC-9457-Antworten.
 *
 * <p>Grundsatz: Was nach aussen geht, ist bereinigt; die Einzelheiten stehen im Log. Eine
 * unerwartete Ausnahme liefert deshalb nur eine Kennung, ueber die sich der Logeintrag
 * finden laesst -- Stacktraces und Klassennamen verraten Angreifern mehr, als sie Nutzern
 * helfen.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Nicht gefunden", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Konflikt", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Ungueltige Anfrage", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validierung fehlgeschlagen",
                "Die Anfrage enthaelt ungueltige Felder");
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String reference = java.util.UUID.randomUUID().toString();
        log.error("Unerwarteter Fehler [{}] bei {} {}", reference, request.getMethod(), request.getRequestURI(), e);

        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Interner Fehler",
                "Unerwarteter Fehler. Die Einzelheiten stehen im Serverlog unter der Kennung " + reference);
        problem.setProperty("reference", reference);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://simple-backup.remo.dev/problems/" + status.value()));
        return problem;
    }
}
