package dev.remo.simplebackup.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Form, in der ein Zugang vom Typ {@code S3_KEYPAIR} in der verschluesselten Ablage liegt.
 *
 * <p>Beide Werte in einem Datensatz, damit sie nicht auseinanderlaufen koennen: Ein Schluessel
 * ohne das zugehoerige Geheimnis ist wertlos, und getrennt abgelegt liesse sich einer von
 * beiden unbemerkt austauschen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record S3Credentials(
        @JsonProperty("accessKeyId") String accessKeyId,
        @JsonProperty("secretAccessKey") String secretAccessKey) {

    /** Ohne die Werte -- diese Darstellung koennte in einer Fehlermeldung landen. */
    @Override
    public String toString() {
        return "S3Credentials[accessKeyId=***]";
    }
}
