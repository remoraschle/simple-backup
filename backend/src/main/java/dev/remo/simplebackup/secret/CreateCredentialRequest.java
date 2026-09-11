package dev.remo.simplebackup.secret;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param secret der Geheimniswert. Nur beim Anlegen und beim Wechseln entgegengenommen;
 *               keine Antwort dieser API gibt ihn jemals zurueck.
 */
public record CreateCredentialRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9 _.-]+", message = "Erlaubt sind Buchstaben, Ziffern, Leerzeichen, Punkt, Bindestrich und Unterstrich")
        String name,

        @NotNull CredentialType type,

        @Size(max = 500) String description,

        @NotBlank @Size(max = 100_000) String secret) {
}
