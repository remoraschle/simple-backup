# ADR-0004: Lokale Ordner und NAS-Shares als erste Quelle

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Sechs Quelltypen sind geplant. Welcher zuerst vollständig funktioniert, entscheidet, woran sich die Architektur zuerst bewähren muss.

## Entscheidung

**Lokale Ordner und NAS-Shares** (M2), gesichert per restic auf ein lokales Ziel und auf S3.

## Begründung

Der Adapter selbst ist trivial — ein Pfad, eine Exclude-Liste. Genau das ist der Punkt: Der erste Meilenstein soll nicht am Adapter scheitern, sondern die Teile härten, die *alle* späteren Quellen brauchen — Scheduler, Lauf-Lebenszyklus, Schritt-Aufteilung, Log-Streaming, Fortschrittsanzeige, Retention, Restore.

Es sind zudem keine externen Zugangsdaten nötig, was die Entwicklung und die Testmatrix erheblich vereinfacht: Ein Testverzeichnis und ein MinIO-Container reichen für einen vollständigen End-to-End-Durchlauf inklusive Restore-Verifikation.

Postgres und GitHub sind wertvoller, bringen aber je eine eigene Schwierigkeit mit (Streaming-Pipeline und Versionskonflikte bzw. API-Discovery und Metadaten-Frage). Beide kommen in M5 — auf ein Fundament, das sich dann bereits bewährt hat.

## Konsequenzen

- Der Wert für den Betrieb entsteht erst ab M2; bis dahin gibt es kein nutzbares Backup.
- Exclude-Muster, Symlink- und Hardlink-Behandlung sowie Sonderrechte (ACLs, erweiterte Attribute) müssen früh geklärt werden — sie betreffen genau diesen Adapter.
- Das Aufsetzen eines NAS-Mounts auf dem Host ist Voraussetzung und gehört ins Betriebshandbuch.
