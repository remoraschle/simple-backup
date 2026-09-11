package dev.remo.simplebackup.engine;

/**
 * Grenzen fuer einen einzelnen Lauf.
 *
 * <p>Einer der Gruende fuer das Runner-Modell: Ein entlaufener {@code rclone}-Sync kann das
 * Backend nicht mehr aushungern, weil er in einem Container mit eigenem Speicherlimit laeuft.
 *
 * @param memoryBytes  Arbeitsspeicher-Obergrenze, {@code null} fuer unbegrenzt
 * @param cpuQuota     Anteil an CPU-Kernen, etwa 1.5, {@code null} fuer unbegrenzt
 * @param networkMode  Netzwerkmodus. {@code "none"} fuer Schritte, die kein Netz brauchen --
 *                     eine lokale Dateisicherung hat im Internet nichts verloren.
 */
public record ResourceLimits(Long memoryBytes, Double cpuQuota, String networkMode) {

    public static final ResourceLimits NONE = new ResourceLimits(null, null, null);

    /** Ohne Netzwerk: fuer Schritte, die ausschliesslich lokal arbeiten. */
    public static ResourceLimits withoutNetwork() {
        return new ResourceLimits(null, null, "none");
    }

    public ResourceLimits {
        if (memoryBytes != null && memoryBytes <= 0) {
            throw new IllegalArgumentException("memoryBytes muss positiv sein");
        }
        if (cpuQuota != null && cpuQuota <= 0) {
            throw new IllegalArgumentException("cpuQuota muss positiv sein");
        }
    }
}
