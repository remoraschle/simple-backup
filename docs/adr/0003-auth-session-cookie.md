# ADR-0003: Session-Cookie-Authentifizierung, ein Admin-Konto

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Das Tool wird von einer Person auf einem eigenen Server betrieben. Es verwaltet allerdings hochwertige Zugangsdaten (GitHub-PATs, S3-Keys, SSH-Keys, DB-Passwörter) und kann Daten löschen — die Authentifizierung darf deshalb nicht nachlässig sein, nur weil es wenige Nutzer gibt.

## Alternativen

| Option | Bewertung |
|---|---|
| **Session-Cookie** | Spring-Security-Standard, wenig Code, `HttpOnly` schützt vor XSS-Token-Diebstahl, sofortiger serverseitiger Logout |
| **JWT im LocalStorage** | Für SPAs verbreitet, löst hier aber kein vorhandenes Problem: Es gibt keine verteilten Services, die zustandslose Tokens prüfen müssten. Dafür ist das Token per XSS auslesbar und nicht widerrufbar |
| **OIDC ab v1** | Elegant bei vorhandenem Authelia/Keycloak, sonst eine zusätzliche Abhängigkeit für ein einziges Konto |

## Entscheidung

**Session-Cookie** (`HttpOnly`, `Secure`, `SameSite=Lax`) über Spring Security. Passwort-Hashing mit Argon2id, Brute-Force-Bremse, Pflicht-Passwortwechsel beim ersten Login, optionale TOTP-Zweitfaktor.

Zwei Rollen: `ADMIN` (alles) und `VIEWER` (nur lesen). Mehr Feingranularität erst, wenn ein konkreter Bedarf auftaucht.

## Konsequenzen

- CSRF-Schutz wird relevant (bei JWT im Header nicht). Spring Securitys CSRF-Token mit `CookieCsrfTokenRepository`, Angulars `HttpClient` unterstützt das `XSRF-TOKEN`-Muster nativ.
- Sessions liegen in der Datenbank (Spring Session JDBC), damit ein Backend-Neustart niemanden ausloggt.
- Die API ist damit primär für den Browser gedacht. Für Automatisierung kommt später ein separates API-Token mit eigenem Geltungsbereich — nicht die Session wiederverwenden.
- OIDC bleibt nachrüstbar: Spring Security kann beides parallel bedienen.
