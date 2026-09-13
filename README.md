# simple-backup

Selbst gehostetes Web-Tool zum Verwalten, Planen, Überwachen und Wiederherstellen von Backups.
Es orchestriert erprobte Linux-Werkzeuge (`restic`, `rsync`, `rclone`, `pg_dump`, `git`)
statt eigene Backup-Technik zu erfinden — die Daten bleiben damit auch ohne dieses Werkzeug
wiederherstellbar.

**Quellen:** GitHub-Repos · S3-Buckets · PostgreSQL · FTP/SFTP · lokale Ordner & NAS-Shares · Blockdevices
**Ziele:** lokale Festplatte · UniFi NAS · S3 / S3-kompatibel · SFTP

## Status

**M0–M5 abgeschlossen.** Das Werkzeug sichert und meldet sich, wenn etwas nicht stimmt:
Pläne mit mehreren Zielen, Zeitplanung, Ausführung in Containern, Lauf-Historie mit
Teilerfolg und Live-Protokoll, vollständige Oberfläche. Dazu Benachrichtigungen über
Pushover und Webhook, Aufbewahrung nach Großvater-Vater-Sohn und ein Totmannschalter, der
Alarm schlägt, wenn eine Sicherung ausbleibt — der gefährlichere Fall gegenüber dem
Fehlschlag, denn ein ausgebliebener Lauf meldet sich nie von selbst.

End-to-End-Tests sichern mit echtem restic, stellen die Dateien wieder her und belegen, dass
die Aufbewahrungsregel eines Plans die Snapshots anderer Pläne unberührt lässt.

Wiederherstellen geht über die Oberfläche: Stand wählen, im Snapshot blättern, ganz oder in
Teilen zurückholen, einzelne Dateien herunterladen. Dazu `restic check` und eine Stichprobe,
die eine echte Datei zurückholt und mit dem Original vergleicht — beides sonntags nachts auch
von selbst, denn eine Prüfung, die man von Hand anstoßen muss, wird genau einmal angestoßen.

Gesichert werden können Verzeichnisse, PostgreSQL-Datenbanken (mit versionspassendem
`pg_dump`, Rollen und geprüftem Dump) und GitHub-Repositories (als Mirror, samt Issues und
Releases).

Als Nächstes M6–M7: S3 und SFTP als Quellen, der Spiegel-Modus mit rsync und die Kür
(Metriken, Konfig-Export, Blockgeräte, OIDC).

## Dokumentation

| | |
|---|---|
| **[Konzept](docs/konzept.md)** | Architektur, Domänenmodell, Quell-Adapter, Sicherheit, Roadmap |
| **[Betrieb](docs/betrieb.md)** | Installation, NAS einbinden, rootless Docker, Fehlersuche |
| **[Restore-Runbook](docs/restore-runbook.md)** | Wie man **ohne** dieses Werkzeug an die Daten kommt |

### Architekturentscheidungen

| ADR | Entscheidung |
|---|---|
| [0001](docs/adr/0001-storage-engine-restic.md) | restic als Storage-Engine, rsync als Mirror-Modus |
| [0002](docs/adr/0002-sidecar-runner.md) | Backup-Schritte laufen in Sidecar-Containern |
| [0003](docs/adr/0003-auth-session-cookie.md) | Session-Cookie-Authentifizierung, ein Admin-Konto |
| [0004](docs/adr/0004-erste-quelle-lokale-pfade.md) | Lokale Ordner und NAS-Shares als erste Quelle |
| [0005](docs/adr/0005-frontend-material-tailwind.md) | Angular Material und Tailwind als Frontend-Stack |
| [0006](docs/adr/0006-build-maven.md) | Maven als Build-Tool |
| [0007](docs/adr/0007-benachrichtigung-pushover.md) | Pushover als primärer Alarmkanal |

## Schnellstart

```bash
cp .env.example .env && $EDITOR .env
mkdir -p secrets && openssl rand -base64 32 > secrets/master_key && chmod 600 secrets/master_key
docker compose up -d
docker compose logs backend | grep -A6 Erststart    # Erstpasswort ablesen
```

> Den Masterkey in den Passwortmanager kopieren. Ohne ihn sind alle gespeicherten
> Zugangsdaten verloren.

Ausführlich in **[docs/betrieb.md](docs/betrieb.md)**.

## Entwicklung

```bash
docker compose -f compose.dev.yaml up -d     # Postgres, MinIO, SFTP, Beispiel-Datenbank

cd backend  && ./mvnw spring-boot:run        # http://localhost:8081
cd frontend && npm start                     # http://localhost:4200
```

Tests:

```bash
cd backend  && ./mvnw verify                 # startet PostgreSQL via Testcontainers
cd frontend && npx ng test --watch=false
```

Ist `restic` installiert, läuft zusätzlich ein End-to-End-Test, der wirklich sichert und
wiederherstellt. Ohne restic wird er übersprungen — dann fehlt allerdings die
aussagekräftigste Prüfung.

Ohne Docker lässt sich eine vorhandene PostgreSQL-Instanz verwenden:

```bash
SIMPLEBACKUP_TEST_DB_URL=jdbc:postgresql://localhost:5432/simplebackup_test ./mvnw verify
```

## Stack

Java 25 · Spring Boot 4.1 · Spring Modulith · Maven · PostgreSQL 18 · Flyway
Angular 22 · Angular Material · Tailwind 4 · Vitest
Docker Compose · GitHub Actions
