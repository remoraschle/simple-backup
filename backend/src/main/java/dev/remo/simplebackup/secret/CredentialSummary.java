package dev.remo.simplebackup.secret;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Sicht, die die API auf einen Zugang hat.
 *
 * <p>Enthaelt keinen Geheimniswert und kann keinen enthalten -- der Typ hat schlicht kein
 * Feld dafuer. Das ist wirksamer als die Absicht, beim Serialisieren daran zu denken.
 */
public record CredentialSummary(
        UUID id,
        String name,
        CredentialType type,
        String description,
        int keyVersion,
        Instant createdAt,
        Instant updatedAt) {

    static CredentialSummary of(Credential credential) {
        return new CredentialSummary(
                credential.getId(),
                credential.getName(),
                credential.getType(),
                credential.getDescription(),
                credential.getKeyVersion(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }
}
