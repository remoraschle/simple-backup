# ADR-0002: Backup-Schritte laufen in Sidecar-Containern

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Das Backend muss `restic`, `rsync`, `rclone`, `git` und `pg_dump` ausführen. Entweder liegen diese Werkzeuge im Backend-Image und werden als Kindprozesse gestartet, oder das Backend erzeugt pro Schritt einen eigenen Container über die Docker-API.

Ausschlaggebend ist `pg_dump`: Es muss mindestens so neu sein wie der Server. Wer Postgres 16 und Postgres 18 sichert, braucht beide Client-Versionen — im Ein-Container-Modell eine Sammlung parallel installierter Pakete mit manueller Pfadauswahl.

## Alternativen

| Option | Bewertung |
|---|---|
| **Fat Image** | Einfach, kein Docker-Zugriff nötig. Aber: feste Tool-Versionen, ~600 MB Image, ein Backend-Neustart killt jeden laufenden Backup-Job |
| **Sidecar-Runner** | Versionspassende Images pro Job, Ressourcen- und Netzgrenzen pro Lauf, Tool-Updates ohne Backend-Deployment, Läufe überleben Backend-Neustarts. Preis: Docker-API-Zugriff |
| **Kubernetes Jobs** | Das richtige Modell im Cluster, reine Betriebslast auf einem Homeserver |

Ursprünglich war „Fat Image für v1 hinter einer `BackupExecutor`-Schnittstelle" empfohlen, um schneller zu einem funktionierenden Backup zu kommen. Dagegen spricht, dass der spätere Wechsel genau die Teile betrifft, die schwer nachzurüsten sind: Host-Pfad-Übersetzung, Wiederanhängen nach Neustart, Aufräumen verwaister Container.

## Entscheidung

**Sidecar-Runner ab v1.** Das `BackupExecutor`-Interface bleibt trotzdem bestehen, mit `DockerJobExecutor` als Produktiv-Implementierung und `LocalProcessExecutor` für Unit-Tests ohne Docker-Daemon.

Der Docker-Socket wird **nie** direkt ins Backend gemountet. Dazwischen liegt ein Socket-Proxy mit Allowlist, der nur `containers/create|start|wait|kill|logs|json`, `DELETE containers` und `images` durchlässt und Create-Requests mit `Privileged`, `CapAdd`, Host-Namespaces oder Mounts außerhalb der Allowlist ablehnt.

## Konsequenzen

- **Host-Pfad-Übersetzung ist Pflicht.** Der Docker-Daemon löst Bind-Mounts gegen das Host-Dateisystem auf; das Backend kennt nur seine eigenen Container-Pfade. Gelöst über Selbst-Inspektion der eigenen Mount-Tabelle (`GET /containers/{self}/json`). Nicht auflösbare Pfade werden beim Anlegen abgelehnt.
- **Secrets dürfen nicht als Environment übergeben werden** — sie wären dauerhaft über `docker inspect` lesbar. Stattdessen werden sie über `PUT /containers/{id}/archive` als Dateien mit Modus `0600` in den erstellten, noch nicht gestarteten Container gelegt und über Datei-Referenzen gelesen (`--password-file`, `PGPASSFILE`, `AWS_SHARED_CREDENTIALS_FILE`).

  *Nachtrag nach der Umsetzung:* Ursprünglich war dafür ein `tmpfs` vorgesehen, damit die Werte nur im RAM liegen. Die Docker-API gibt das nicht her — ein `tmpfs` wird erst beim Start gemountet und überdeckt vorher kopierte Dateien, und nach dem Start zu kopieren kommt zu spät, weil das Kommando dann schon läuft. Ein wartendes Entrypoint-Skript würde nur im eigenen Runner-Image greifen, nicht in `postgres:N-alpine`. Der wesentliche Gewinn gegenüber Umgebungsvariablen bleibt bestehen; die Werte berühren dafür kurzzeitig die Schreibschicht des Containers, die mit `docker rm` verschwindet.
- **`AutoRemove` bleibt aus**, sonst gehen Exit-Code und Logs verloren. Explizites Entfernen nach Auswertung, plus Label-Reaper beim Start.
- Ein zusätzlicher Meilenstein (M1) vor dem ersten echten Backup.
- Die Entwicklungsumgebung braucht einen Docker-Daemon. Der `LocalProcessExecutor` hält schnelle Tests ohne ihn möglich.
- **Betriebsempfehlung:** rootless Docker oder Podman, um die Rest-Eskalation zu begrenzen (offen als E-6).
