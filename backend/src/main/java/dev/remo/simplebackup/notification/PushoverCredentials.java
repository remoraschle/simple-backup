package dev.remo.simplebackup.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Form, in der ein Zugang vom Typ {@code PUSHOVER} in der verschluesselten Ablage liegt.
 *
 * <p>Beides zusammen in einem Datensatz: Der Anwendungs-Token ohne den Benutzerschluessel
 * ist nutzlos, und getrennt abgelegt liefe man Gefahr, den einen zu wechseln und den
 * anderen zu vergessen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PushoverCredentials(
        @JsonProperty("apiToken") String apiToken,
        @JsonProperty("userKey") String userKey) {

    PushoverCredentials {
        if (apiToken == null || apiToken.isBlank() || userKey == null || userKey.isBlank()) {
            throw new IllegalArgumentException("""
                    Ein Pushover-Zugang besteht aus zwei Werten und wird als JSON hinterlegt: \
                    {"apiToken": "...", "userKey": "..."}""");
        }
    }

    /** Ohne die Werte -- diese Darstellung koennte in einer Fehlermeldung landen. */
    @Override
    public String toString() {
        return "PushoverCredentials[apiToken=***]";
    }
}
