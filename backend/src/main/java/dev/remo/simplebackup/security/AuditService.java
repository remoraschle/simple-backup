package dev.remo.simplebackup.security;

import dev.remo.simplebackup.shared.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Schreibt das Pruefprotokoll fuer alle veraendernden Aktionen.
 *
 * <p>Geschrieben wird in einer eigenen Transaktion, siehe {@link AuditWriter}.
 *
 * <p>Das Detailfeld laeuft durch den Redaktor, weil dort sonst leicht ein Zugangswert
 * mitgeschrieben wuerde.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditWriter writer;
    private final SecretRedactor redactor;
    private final ObjectMapper objectMapper;

    AuditService(AuditWriter writer, SecretRedactor redactor, ObjectMapper objectMapper) {
        this.writer = writer;
        this.redactor = redactor;
        this.objectMapper = objectMapper;
    }

    /**
     * Haelt eine Aktion fest.
     *
     * <p><b>Scheitert nie nach aussen.</b> Das Detailfeld liegt als JSONB in der Datenbank;
     * ein Pfad oder ein "100%" ist kein gueltiges JSON und liess die gesamte Anfrage mit
     * einem Serverfehler enden -- die Aktion scheiterte am Protokoll ueber die Aktion. Der
     * Wert wird deshalb als JSON-Zeichenkette abgelegt, und ein Fehler beim Schreiben wird
     * laut protokolliert statt weitergereicht: Ein unvollstaendiges Pruefprotokoll ist
     * schlecht, eine Anwendung, die deswegen nichts mehr tut, ist schlechter.
     *
     * @param detail freier Text, etwa ein Pfad oder ein Anteil. Darf {@code null} sein.
     */
    public void record(String username, String action, String entityType, String entityId, String detail) {
        try {
            writer.write(new AuditLog(
                    username,
                    action,
                    entityType,
                    entityId,
                    detail == null ? null : objectMapper.writeValueAsString(redactor.redact(detail)),
                    currentClientIp()));

        } catch (RuntimeException e) {
            log.error("Pruefprotokoll: {} von {} auf {} {} liess sich nicht schreiben",
                    action, username, entityType, entityId, e);
        }
    }

    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        // Aktionen des Schedulers laufen ohne Anfragekontext.
        return null;
    }
}
