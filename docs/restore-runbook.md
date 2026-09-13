# Restore-Runbook

> **Dieses Dokument beschreibt, wie man ohne simple-backup an die Daten kommt.**
>
> Wenn der Server abgebrannt ist, läuft kein Spring Boot mehr — dann hilft nur eine
> Anleitung, die ohne das Werkzeug auskommt. Dieses Dokument gehört ausgedruckt oder in den
> Passwortmanager, nicht ausschließlich auf den Server, den es zu retten gilt.

---

## Was du im Ernstfall brauchst

| Gegenstand | Wo er liegen sollte | Ohne ihn |
|---|---|---|
| **restic-Repository-Passwort** | Passwortmanager | Die Backups sind **unwiederbringlich verloren.** Es gibt keine Hintertür. |
| Zugang zum Ziel (S3-Keys, SSH-Key, Pfad zur Platte) | Passwortmanager | Kein Zugriff auf das Repository |
| **Masterkey** von simple-backup | Passwortmanager | Die Zugangsdaten *in* der Anwendung sind verloren — die Backups selbst nicht |
| **Konfigurationsarchiv** (`.sbexp`) und sein Passwort | Getrennt vom Server, Passwort im Passwortmanager | Die Anwendung muss von Hand neu eingerichtet werden. Die Backups selbst sind nicht betroffen |
| Dieses Dokument | Ausgedruckt oder außerhalb des Servers | Du musst dich durch die restic-Dokumentation arbeiten |

Der Masterkey ist **nicht** dasselbe wie das Repository-Passwort. Der Masterkey schützt die
in der Anwendung gespeicherten Zugangsdaten; das Repository-Passwort schützt die Backups.
Für einen Restore brauchst du nur Letzteres.

---

## 1. restic besorgen

Ein einzelnes statisch gelinktes Programm, keine Installation nötig:

```bash
# Paketverwaltung
apt install restic          # Debian/Ubuntu
brew install restic         # macOS

# oder direkt
wget https://github.com/restic/restic/releases/latest/download/restic_linux_amd64.bz2
bunzip2 restic_linux_amd64.bz2 && chmod +x restic_linux_amd64
```

## 2. Repository ansprechen

```bash
export RESTIC_PASSWORD='das-repository-passwort'

# Lokale Platte oder NAS-Mount
export RESTIC_REPOSITORY='/mnt/nas/backups/fotos'

# S3 oder S3-kompatibel
export RESTIC_REPOSITORY='s3:https://s3.eu-central-1.amazonaws.com/mein-bucket/pfad'
export AWS_ACCESS_KEY_ID='...'
export AWS_SECRET_ACCESS_KEY='...'

# SFTP
export RESTIC_REPOSITORY='sftp:backup@nas.local:/volume1/backups/fotos'
```

## 3. Nachsehen, was da ist

```bash
restic snapshots                      # alle Sicherungspunkte mit Datum
restic ls latest                      # Inhalt des jüngsten
restic ls 4bba301e /etc               # Inhalt eines bestimmten, ab einem Pfad
restic find '*.kdbx'                  # eine Datei suchen, über alle Snapshots
```

## 4. Wiederherstellen

```bash
# Alles aus dem jüngsten Snapshot
restic restore latest --target /wohin/damit

# Nur ein Unterverzeichnis
restic restore latest --target /wohin/damit --include /srv/fotos/2024

# Stand von vor drei Wochen
restic snapshots                                  # passende ID heraussuchen
restic restore 4bba301e --target /wohin/damit

# Eine einzelne Datei auf die Standardausgabe, ohne alles auszupacken
restic dump latest /srv/fotos/urlaub.jpg > urlaub.jpg
```

## 5. PostgreSQL-Sicherungen zurückspielen

Datenbanken liegen als `pg_dump`-Archiv im Repository:

```bash
restic dump latest /datenbank.dump > datenbank.dump

# Erst nachsehen, was drin ist -- das prüft zugleich, ob das Archiv intakt ist
pg_restore --list datenbank.dump | head

# In eine NEUE Datenbank einspielen, nicht in die bestehende
createdb wiederhergestellt
pg_restore --dbname=wiederhergestellt --no-owner datenbank.dump
```

Rollen und Tablespaces liegen getrennt daneben, weil `pg_dump` sie nicht enthält:

```bash
restic dump latest /globals.sql | psql --dbname=postgres
```

## 6. Git-Sicherungen

Repositories liegen als Spiegel oder Bundle vor:

```bash
restic dump latest /repos/meinprojekt.bundle > meinprojekt.bundle
git clone meinprojekt.bundle meinprojekt
```

Issues, Pull Requests und Releases liegen als JSON daneben (`/repos/meinprojekt.meta.json`).
Ein Git-Spiegel enthält sie nicht — das ist keine Lücke dieses Werkzeugs, sondern eine
Eigenschaft von Git.

---

## Prüfen, ob ein Backup überhaupt gut ist

Nicht erst im Ernstfall. Das erledigt simple-backup zwar automatisch, aber von Hand geht es
genauso:

```bash
restic check                          # Struktur prüfen, schnell
restic check --read-data-subset=5%    # 5 % der Daten wirklich lesen
```

---

## Wenn simple-backup selbst wiederhergestellt werden soll

Die Anwendung ist ersetzbar — ihre Datenbank enthält Konfiguration, keine Backupdaten. Für
die Sicherungen selbst braucht man sie nicht; die Schritte 1 bis 6 kommen ohne sie aus.

**Der schnelle Weg: das Konfigurationsarchiv.** Leere Installation hochfahren, anmelden,
unter **Einstellungen → Archiv** die `.sbexp`-Datei einspielen. Danach stehen Quellen,
Ziele, Pläne, Regeln und Kanäle wieder, einschließlich aller Zugangsdaten — auch der
Repository-Passwörter, mit denen sich die vorhandenen Repositories weiter benutzen lassen.

Das Archiv hängt am Passwort, mit dem es angelegt wurde, und nicht am Masterkey. Genau
deshalb hilft es in dem Fall, in dem die Datenbank allein nichts mehr wert ist. Pläne
behalten beim Einspielen ihre Kennung; deshalb findet der Abgleich eines Ziels danach die
alten Stände wieder und ordnet sie dem richtigen Plan zu.

**Der Weg über die Datenbank**, mit Masterkey und Dump:

```bash
docker compose up -d db
pg_restore --dbname=simplebackup --no-owner simplebackup.dump
# secrets/master_key wiederherstellen, dann:
docker compose up -d
```

**Ohne Masterkey und ohne Archiv** lassen sich die gespeicherten Zugangsdaten nicht
entschlüsseln. Dann: Datenbank neu aufsetzen, Pläne neu anlegen, Zugangsdaten neu eintragen.
Die Backups selbst bleiben davon unberührt und sind über die Schritte oben weiterhin
erreichbar — **sofern das Repository-Passwort bekannt ist.**

Deshalb: Repository-Passwort und Masterkey gehören in den Passwortmanager, nicht nur in
dieses Werkzeug.
