package dev.remo.simplebackup.security;

import dev.remo.simplebackup.shared.SecretRedactor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Schreibt das Pruefprotokoll fuer alle veraendernden Aktionen.
 *
 * <p>In einer eigenen Transaktion: Ein Eintrag muss auch dann erhalten bleiben, wenn die
 * ausloesende Aktion anschliessend zurueckgerollt wird -- gerade der fehlgeschlagene
 * Loeschversuch ist der interessante.
 *
 * <p>Das Detailfeld laeuft durch den Redaktor, weil dort sonst leicht ein Zugangswert
 * mitgeschrieben wuerde.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final SecretRedactor redactor;

    AuditService(AuditLogRepository repository, SecretRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String action, String entityType, String entityId, String detailJson) {
        repository.save(new AuditLog(
                username,
                action,
                entityType,
                entityId,
                detailJson == null ? null : redactor.redact(detailJson),
                currentClientIp()));
    }

    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        // Aktionen des Schedulers laufen ohne Anfragekontext.
        return null;
    }
}
