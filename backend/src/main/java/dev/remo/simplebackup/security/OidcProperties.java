package dev.remo.simplebackup.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wie eine Anmeldung ueber einen Anbieter auf einen Benutzer dieser Anwendung abgebildet wird.
 *
 * <p>Der Anbieter selbst wird unter {@code spring.security.oauth2.client} konfiguriert; ohne
 * Registrierung dort ist dieser ganze Weg abgeschaltet. Hier steht nur, was diese Anwendung
 * mit den Angaben anfaengt.
 *
 * @param displayName    Aufschrift des Knopfes auf der Anmeldeseite
 * @param registrationId Kennung der Registrierung unter {@code spring.security.oauth2.client}
 * @param usernameClaim  Anspruch, aus dem der Anmeldename kommt. Muss stabil sein: Aendert er
 *                       sich beim Anbieter, entsteht hier ein zweiter Benutzer statt einer
 *                       Umbenennung.
 * @param groupsClaim    Anspruch mit den Gruppen des Benutzers
 * @param adminGroup     Wer in dieser Gruppe ist, wird Administrator, alle anderen duerfen
 *                       nur lesen. Ohne Angabe bekommt ein neuer Benutzer Leserechte, und
 *                       ein bekannter behaelt seine Rolle -- eine Anmeldung soll niemanden
 *                       versehentlich zum Administrator machen.
 * @param requiredGroup  Wer nicht in dieser Gruppe ist, kommt gar nicht herein. Ohne Angabe
 *                       darf jeder herein, den der Anbieter durchlaesst -- was richtig ist,
 *                       wenn der Anbieter ohnehin nur fuer diese Anwendung zustaendig ist,
 *                       und falsch, wenn dort das halbe Haus ein Konto hat.
 */
@ConfigurationProperties(prefix = "simplebackup.oidc")
public record OidcProperties(
        String displayName,
        String registrationId,
        String usernameClaim,
        String groupsClaim,
        String adminGroup,
        String requiredGroup) {

    public OidcProperties {
        displayName = orDefault(displayName, "Single Sign-on");
        registrationId = orDefault(registrationId, "oidc");
        usernameClaim = orDefault(usernameClaim, "preferred_username");
        groupsClaim = orDefault(groupsClaim, "groups");
        adminGroup = blankToNull(adminGroup);
        requiredGroup = blankToNull(requiredGroup);
    }

    /** Adresse, auf die der Knopf auf der Anmeldeseite zeigt. */
    public String authorizationUrl() {
        return "/oauth2/authorization/" + registrationId;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
