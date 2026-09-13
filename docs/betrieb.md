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
Dafür gibt es unter **Einstellungen → Archiv** die Ausgabe der gesamten Konfiguration als
eine Datei: Quellen, Ziele, Pläne, Aufbewahrungsregeln, Kanäle und die Zugangsdaten,
verschlüsselt mit einem selbst gewählten Passwort.

Dieses Archiv hängt **nicht** am Masterkey — genau dafür ist es da. Ohne ihn ist die eigene
Datenbank wertlos: Alle Zugangsdaten darin sind unlesbar, und damit kommt man an kein
einziges Repository mehr heran.

Einspielen geht auf derselben Seite. Angelegt wird nur, was fehlt; ein Eintrag, dessen Name
schon vergeben ist, gilt als derselbe und wird übersprungen. Ein zweiter Aufruf ändert
deshalb nichts mehr. Pläne behalten dabei ihre Kennung, damit ihnen ihre bisherigen
Snapshots nicht fremd werden.

Das Archiv ist so viel wert wie der Zugriff auf sämtliche Ziele. Es gehört **getrennt von
den Backup-Zielen** aufbewahrt, und das Passwort nicht mit ihm zusammen — sonst hängen im
Ernstfall beide am selben Haken.

Wer lieber auf der Ebene der Datenbank sichert, braucht beides:

```bash
docker compose exec db pg_dump -U simplebackup -Fc simplebackup > simplebackup.dump
```

…und `secrets/master_key`. Ohne den Schlüssel ist der Dump wertlos.

## Überwachung

Unter `/actuator/prometheus` stehen Kennzahlen je Plan; der Endpunkt ist dem Administrator
vorbehalten, weil dort Planbezeichnungen und Zeitpunkte sichtbar werden.

Die wichtigste ist `simplebackup_plan_last_success_age_seconds`. Sie beantwortet als
einzige die Frage, die im Ernstfall zählt: Wie alt ist die neueste Sicherung, die sich
wirklich zurückspielen ließe? Ein Zähler fehlgeschlagener Läufe beantwortet sie nicht — ein
Plan, der gar nicht mehr startet, erzeugt keine Fehlschläge. Deshalb wird das Alter auch
dann gemeldet, wenn ein Plan noch nie erfolgreich war; es zählt dann ab seiner Anlage.

Der erwartete Abstand kommt als eigene Kennzahl mit, sodass die Alarmregel ohne fest
verdrahtete Schwellwerte je Plan auskommt:

```yaml
- alert: SicherungUeberfaellig
  expr: simplebackup_plan_expected_interval_seconds > 0
    and simplebackup_plan_last_success_age_seconds
        > simplebackup_plan_expected_interval_seconds * 2
```

Daneben: Dauer, gelesene und geschriebene Datenmenge des letzten Laufs, ein Zähler je
Ausgang (`SUCCESS`, `PARTIAL`, `FAILED`, …) und der freie Platz auf den Zielen.

Der eingebaute Totmannschalter meldet denselben Fall über die eingerichteten Kanäle. Beides
nebeneinander ist Absicht: Die Meldung erreicht auch den, der kein Prometheus betreibt.

## Ganze Datenträger sichern

Nur mit einem Docker-Daemon **mit** Wurzelrechten — rootless kann kein Gerät durchreichen,
und die Oberfläche bietet den Quelltyp dort gar nicht erst an.

Zwei Stellen, beide nötig:

```yaml
  backend:
    environment:
      SIMPLEBACKUP_DEVICES: /dev/sdb
    devices:
      - /dev/sdb:/dev/sdb:r
```

Standardmäßig ist die Liste leer. Das ist Absicht: Ohne sie entschiede der Inhalt eines
Formularfelds darüber, welche Platte roh gelesen wird — vorbei an allen Dateirechten.
Niemals `privileged: true`; das gäbe dem Container gleich den ganzen Host.

Das Abbild entsteht im Zwischenverzeichnis, bevor es gesichert wird. Dort muss also so viel
Platz frei sein, wie die Platte belegt. Für „jede Nacht die ganze Platte" ist eine
dateibasierte Quelle fast immer die bessere Antwort; für einen Bootsektor oder ein fremdes
Dateisystem gibt es nur diesen Weg.

## Anmeldung über einen Anbieter (OIDC)

Als Alternative zum Anmeldeformular, etwa mit Authelia, Keycloak oder Authentik. In der
`.env`:

```bash
SPRING_PROFILES=oidc
OIDC_ISSUER=https://auth.example.org
OIDC_CLIENT_ID=simple-backup
OIDC_CLIENT_SECRET=...
OIDC_ADMIN_GROUP=backup-admins
```

Als Rücksprungadresse trägt man beim Anbieter `https://<host>/login/oauth2/code/oidc` ein.

Ohne das Profil existiert dieser Weg nicht — die Anmeldeseite zeigt den Knopf dann auch
nicht. Ein Knopf, der ins Leere führt, wäre schlimmer als keiner.

Der Anbieter sagt, **wer** jemand ist; was er darf, entscheidet diese Anwendung. Wer sich
zum ersten Mal anmeldet, wird angelegt und bekommt Leserechte. Administrator wird niemand
allein dadurch, dass er sich anmeldet. Ist `OIDC_ADMIN_GROUP` gesetzt, folgt die Rolle dem
Anbieter — auch nach unten: Wem dort die Gruppe entzogen wurde, der ist hier beim nächsten
Anmelden kein Administrator mehr.

Mit `OIDC_REQUIRED_GROUP` kommt nur herein, wer in dieser Gruppe ist. Das ist dort richtig,
wo beim Anbieter das halbe Haus ein Konto hat.

Das Anmeldeformular bleibt daneben bestehen. Das ist Absicht: Fällt der Anbieter aus, kommt
man trotzdem noch an seine Sicherungen.

## Fehlersuche

| Symptom | Ursache |
|---|---|
| Backend startet nicht, Meldung zum Masterkey | `secrets/master_key` fehlt oder ist nicht 32 Byte lang. Neu erzeugen mit `openssl rand -base64 32` — **nur**, solange noch keine Zugangsdaten gespeichert sind |
| Anmeldung schlägt trotz richtigem Passwort fehl | `COOKIE_SECURE=true` ohne TLS. Auf `false` setzen oder TLS einrichten |
| Konto gesperrt | Nach fünf Fehlversuchen für 15 Minuten. Die Sperre läuft von selbst ab |
| Runner startet nicht | Prüfen, ob `dockerproxy` läuft und `DOCKER_HOST` auf ihn zeigt |
| Quelle nicht sicherbar, Pfad nicht auflösbar | Das Verzeichnis ist nicht am **Backend** gemountet. Siehe Installation |
| Quelltyp „Ganzer Datenträger" ausgegraut | Rootless Docker, oder kein Gerät unter `SIMPLEBACKUP_DEVICES` freigegeben. Die Oberfläche schreibt den Grund daneben |
| Anmeldeknopf für den Anbieter fehlt | Profil `oidc` nicht aktiv oder `OIDC_CLIENT_ID` leer |

## Wiederherstellung

Siehe **[restore-runbook.md](restore-runbook.md)** — dort steht insbesondere, wie man ohne
dieses Werkzeug an die Daten kommt.
