package dev.remo.simplebackup.secret;

import dev.remo.simplebackup.security.AuditService;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verwaltung der Zugangsdaten.
 *
 * <p>Alle Rueckgaben sind {@link CredentialSummary}. Dieser Typ hat kein Feld fuer den
 * Geheimniswert, kann also konstruktionsbedingt keines herausgeben -- verlaesslicher, als
 * beim Serialisieren jedes Mal daran zu denken.
 *
 * <p>Wer nur lesen darf, sieht hier folglich Namen, Typen und Zeitpunkte, aber keine Werte.
 */
@RestController
@RequestMapping("/api/credentials")
class CredentialController {

    private final CredentialService credentialService;
    private final AuditService auditService;

    CredentialController(CredentialService credentialService, AuditService auditService) {
        this.credentialService = credentialService;
        this.auditService = auditService;
    }

    @GetMapping
    List<CredentialSummary> list() {
        return credentialService.findAll();
    }

    @GetMapping("/{id}")
    CredentialSummary get(@PathVariable UUID id) {
        return credentialService.find(id);
    }

    @PostMapping
    ResponseEntity<CredentialSummary> create(@Valid @RequestBody CreateCredentialRequest request,
            Principal principal) {

        CredentialSummary created = credentialService.create(
                request.name(), request.type(), request.description(), request.secret());

        auditService.record(principal.getName(), "CREDENTIAL_CREATED", "credential",
                created.id().toString(), "{\"name\":\"%s\",\"type\":\"%s\"}"
                        .formatted(created.name(), created.type()));

        return ResponseEntity.created(URI.create("/api/credentials/" + created.id())).body(created);
    }

    /** Ersetzt nur den Wert; Name und Typ bleiben, damit bestehende Verweise gueltig bleiben. */
    @PutMapping("/{id}/secret")
    CredentialSummary rotate(@PathVariable UUID id, @Valid @RequestBody UpdateSecretRequest request,
            Principal principal) {

        CredentialSummary updated = credentialService.rotate(id, request.secret());
        auditService.record(principal.getName(), "CREDENTIAL_ROTATED", "credential", id.toString(), null);
        return updated;
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
        credentialService.delete(id);
        auditService.record(principal.getName(), "CREDENTIAL_DELETED", "credential", id.toString(), null);
        return ResponseEntity.noContent().build();
    }
}
