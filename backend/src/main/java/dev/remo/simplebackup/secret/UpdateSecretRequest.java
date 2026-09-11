package dev.remo.simplebackup.secret;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSecretRequest(@NotBlank @Size(max = 100_000) String secret) {
}
