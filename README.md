# simple-backup

Selbst gehostetes Web-Tool zum Verwalten, Planen, Überwachen und Wiederherstellen von Backups.
Es orchestriert erprobte Linux-Werkzeuge (`restic`, `rsync`, `rclone`, `pg_dump`, `git`) statt eigene Backup-Technik zu erfinden.

**Quellen:** GitHub-Repos · S3-Buckets · PostgreSQL · FTP/SFTP · lokale Ordner & NAS-Shares · Blockdevices
**Ziele:** lokale Festplatte · UniFi NAS · S3 / S3-kompatibel · SFTP

## Status

Konzeptphase. Es gibt noch keinen Code.

👉 **[docs/konzept.md](docs/konzept.md)** — Architektur, Domänenmodell, Adapter, Sicherheit, Stack, Roadmap und die offenen Entscheidungen.

## Stack (geplant)

Java 25 · Spring Boot 4.1 · PostgreSQL 18 · Angular 22 · Docker Compose
