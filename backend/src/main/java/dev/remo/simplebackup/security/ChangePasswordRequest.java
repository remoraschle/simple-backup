package dev.remo.simplebackup.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param newPassword Mindestlaenge statt Zeichenklassen-Vorgaben: Laenge traegt mehr zur
 *                    Staerke bei, und erzwungene Sonderzeichen fuehren erfahrungsgemaess zu
 *                    schlechteren, aufgeschriebenen Passwoertern.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 12, max = 200, message = "Das neue Passwort muss mindestens 12 Zeichen haben")
        String newPassword) {
}
