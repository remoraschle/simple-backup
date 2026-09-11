package dev.remo.simplebackup.security;

/**
 * Was das Frontend ueber die laufende Sitzung wissen muss.
 *
 * @param authenticated      false, wenn niemand angemeldet ist
 * @param username           Anmeldename, sonst null
 * @param role               Rolle, sonst null
 * @param mustChangePassword true zwingt die Oberflaeche in den Passwortwechsel
 */
public record SessionInfo(boolean authenticated, String username, UserRole role, boolean mustChangePassword) {

    static SessionInfo anonymous() {
        return new SessionInfo(false, null, null, false);
    }
}
