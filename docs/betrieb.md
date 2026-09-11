# Betrieb

## Voraussetzungen

- Docker mit Compose (rootful oder rootless — beides wird unterstützt)
- Etwa 1 GB RAM für das Backend, dazu Platz für das Staging-Volume
- Ein Reverse Proxy für TLS, falls die Oberfläche von außen erreichbar sein soll

## Installation

```bash
git clone https://github.com/remoraschle/simple-backup.git
cd simple-backup

cp .env.example .env
$EDITOR .env                                   # Datenbankpasswort setzen

mkdir -p secrets
openssl rand -base64 32 > secrets/master_key
chmod 600 secrets/master_key
```

> **Den Masterkey jetzt in den Passwortmanager kopieren.** Ohne ihn sind alle später
> gespeicherten Zugangsdaten verloren. Er wird nirgends ein zweites Mal angezeigt.

Anschließend die zu sichernden Verzeichnisse in `compose.yaml` unter `backend` eintragen —
Quellen immer mit `:ro`. Diese Einträge sind auch dann nötig, wenn das Backend die Daten
selbst nie liest: Es leitet daraus ab, welcher Container-Pfad zu welchem Host-Pfad gehört,
und nur so kann ein Runner-Container sie erreichen. Was das Backend nicht gemountet hat,
kann kein Runner sichern.

```bash
docker compose up -d
docker compose logs backend | grep -A6 Erststart      # Erstpasswort ablesen
```

Die Oberfläche läuft auf `http://localhost:8080`. Beim ersten Anmelden muss das Passwort
gewechselt werden.

## Hinter einem Reverse Proxy

`COOKIE_SECURE=true` in der `.env` setzen, sobald TLS aktiv ist. Vorher nicht: Bei `true`
sendet der Browser das Sitzungscookie nur über HTTPS, eine Anmeldung über HTTP wäre dann
nicht mehr möglich.

## NAS einbinden

Der Share wird auf dem **Host** gemountet und von dort durchgereicht. Im Container zu
mounten bräuchte `CAP_SYS_ADMIN` — vermeidbar, also vermieden.

```bash
# /etc/fstab
//nas.local/backups /mnt/nas cifs credentials=/etc/samba/nas.cred,uid=1000,gid=1000 0 0
```

Dann in `compose.yaml` unter `backend` ergänzen: `- /mnt/nas:/mnt/nas`

## Rootless Docker

Wird unterstützt und ist die sicherere Variante: Eine Eskalation über die Docker-API bliebe
auf den unprivilegierten Docker-Benutzer begrenzt statt auf `root`. Zwei Unterschiede sind
zu beachten:

- **Blockdevice-Sicherungen sind nicht möglich.** Die Anwendung erkennt den Modus beim Start
  und bietet diesen Quelltyp gar nicht erst an, statt nachts an fehlenden Rechten zu
  scheitern.
- **Dateirechte werden abgebildet.** Durch das User-Namespace-Mapping ist eine Datei, die dem
  Host-Benutzer gehört, im Runner unter Umständen nicht lesbar. Deshalb läuft der
  Verbindungstest einer Quelle im Runner-Container und nicht im Backend — nur so prüft er
  die Rechte, die später tatsächlich gelten.

Socket-Pfad in `compose.yaml` anpassen:

```yaml
  dockerproxy:
    volumes:
      - ${XDG_RUNTIME_DIR}/docker.sock:/var/run/docker.sock:ro
```

## Aktualisieren

```bash
docker compose pull && docker compose up -d
```

Flyway wandert beim Start automatisch auf den neuen Schemastand. Ein Backend-Neustart
beendet **keinen** laufenden Backup-Lauf: Die Runner-Container laufen weiter, und das
Backend hängt sich beim Hochfahren wieder an sie an.

## Sichern der Anwendung selbst

Ein Backup-Werkzeug, dessen eigene Konfiguration nicht gesichert ist, ist nur halb fertig.
Nötig sind zwei Dinge:

```bash
docker compose exec db pg_dump -U simplebackup -Fc simplebackup > simplebackup.dump
```

…und `secrets/master_key`. Ohne den Schlüssel ist der Datenbank-Dump wertlos.

Bewahre beides **getrennt von den Backup-Zielen** auf — sonst hängen im Ernstfall beide am
selben Haken.

## Fehlersuche

| Symptom | Ursache |
|---|---|
| Backend startet nicht, Meldung zum Masterkey | `secrets/master_key` fehlt oder ist nicht 32 Byte lang. Neu erzeugen mit `openssl rand -base64 32` — **nur**, solange noch keine Zugangsdaten gespeichert sind |
| Anmeldung schlägt trotz richtigem Passwort fehl | `COOKIE_SECURE=true` ohne TLS. Auf `false` setzen oder TLS einrichten |
| Konto gesperrt | Nach fünf Fehlversuchen für 15 Minuten. Die Sperre läuft von selbst ab |
| Runner startet nicht | Prüfen, ob `dockerproxy` läuft und `DOCKER_HOST` auf ihn zeigt |
| Quelle nicht sicherbar, Pfad nicht auflösbar | Das Verzeichnis ist nicht am **Backend** gemountet. Siehe Installation |

## Wiederherstellung

Siehe **[restore-runbook.md](restore-runbook.md)** — dort steht insbesondere, wie man ohne
dieses Werkzeug an die Daten kommt.
