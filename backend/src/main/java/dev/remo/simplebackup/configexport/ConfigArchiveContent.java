package dev.remo.simplebackup.configexport;

import dev.remo.simplebackup.catalog.CatalogRequests;
import dev.remo.simplebackup.notification.NotificationRequests;
import dev.remo.simplebackup.secret.CredentialType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Der Inhalt des Archivs, bevor er verschluesselt wird.
 *
 * <p>Jeder Eintrag traegt seine urspruengliche Kennung und daneben genau das, was die API
 * beim Anlegen entgegennimmt. Das ist Absicht: Beim Einspielen laeuft alles durch dieselben
 * Dienste und dieselben Pruefungen wie beim Anlegen von Hand. Ein Archiv kann also keine
 * Konfiguration in die Datenbank bringen, die ueber die Oberflaeche unmoeglich waere.
 *
 * <p>Die Kennungen dienen nur dazu, die Verweise untereinander aufzuloesen -- ein Plan
 * verweist auf eine Quelle, eine Quelle auf einen Zugang. Beim Einspielen entstehen neue
 * Kennungen, und die Verweise werden mitgezogen.
 *
 * @param formatVersion Aufbau dieses Inhalts. Getrennt von der Version der Huelle in
 *                      {@link PasswordArchive}: Die eine beschreibt die Verschluesselung,
 *                      die andere die Felder.
 * @param exportedAt    wann ausgegeben wurde -- beim Einspielen sieht man, wie alt der
 *                      Stand ist, den man sich gerade holt
 */
record ConfigArchiveContent(
        int formatVersion,
        Instant exportedAt,
        String application,
        List<ExportedCredential> credentials,
        List<ExportedRetentionPolicy> retentionPolicies,
        List<ExportedSource> sources,
        List<ExportedTarget> targets,
        List<ExportedPlan> plans,
        List<ExportedChannel> channels) {

    static final int FORMAT_VERSION = 1;
    static final String APPLICATION = "simple-backup";

    /**
     * Ein Zugang samt Klartext.
     *
     * <p>Der einzige Ort dieser Anwendung, an dem Geheimnisse ausserhalb eines
     * Runner-Containers im Klartext stehen. Genau deshalb liegt dieser Inhalt nie unverpackt
     * vor: Er entsteht im Speicher, wird sofort verschluesselt und erst beim Einspielen
     * wieder geoeffnet.
     */
    record ExportedCredential(
            UUID id,
            String name,
            CredentialType type,
            String description,
            String secret) {
    }

    record ExportedRetentionPolicy(UUID id, CatalogRequests.SaveRetentionPolicy definition) {
    }

    record ExportedSource(UUID id, CatalogRequests.SaveSource definition) {
    }

    record ExportedTarget(UUID id, CatalogRequests.SaveTarget definition) {
    }

    record ExportedPlan(UUID id, CatalogRequests.SavePlan definition) {
    }

    record ExportedChannel(UUID id, NotificationRequests.SaveChannel definition) {
    }
}
