# simple-backup

Selbst gehostetes Web-Tool zum Verwalten, Planen, Überwachen und Wiederherstellen von Backups.
Es orchestriert erprobte Linux-Werkzeuge (`restic`, `rsync`, `rclone`, `pg_dump`, `git`) statt eigene Backup-Technik zu erfinden.

**Quellen:** GitHub-Repos · S3-Buckets · PostgreSQL · FTP/SFTP · lokale Ordner & NAS-Shares · Blockdevices
**Ziele:** lokale Festplatte · UniFi NAS · S3 / S3-kompatibel · SFTP

## Status

Konzeptphase. Es gibt noch keinen Code.

👉 **[docs/konzept.md](docs/konzept.md)** — Architektur, Domänenmodell, Adapter, Sicherheit, Stack, Roadmap.

## Architekturentscheidungen

| ADR | Entscheidung |
|---|---|
| [0001](docs/adr/0001-storage-engine-restic.md) | restic als Storage-Engine, rsync als Mirror-Modus |
| [0002](docs/adr/0002-sidecar-runner.md) | Backup-Schritte laufen in Sidecar-Containern |
| [0003](docs/adr/0003-auth-session-cookie.md) | Session-Cookie-Authentifizierung, ein Admin-Konto |
| [0004](docs/adr/0004-erste-quelle-lokale-pfade.md) | Lokale Ordner und NAS-Shares als erste Quelle |

## Stack (geplant)

Java 25 · Spring Boot 4.1 · PostgreSQL 18 · Angular 22 · Docker Compose
