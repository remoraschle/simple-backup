# ADR-0001: restic als Storage-Engine, rsync als Mirror-Modus

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Gesichert werden soll auf lokale Platten, ein UniFi NAS und S3-kompatible Ziele. Gefordert sind Versionierung („Stand von vor drei Wochen"), Verschlüsselung am Ziel und ein Aufbewahrungskonzept. Die naheliegende Idee, alles mit `rsync` zu machen, scheitert an drei Punkten: rsync kennt keine Snapshots, dedupliziert nicht und spricht kein S3.

## Alternativen

| Option | Bewertung |
|---|---|
| **restic** | S3 nativ, inhaltsbasierte Deduplizierung, immer verschlüsselt, `forget --keep-*` für GFS, `check --read-data` für Integrität, `--stdin` für Pipe-Backups, **keine Software am Ziel nötig** |
| **borg** | Funktional ebenbürtig und reifer, aber kein natives S3 und ein `borg`-Binary am Ziel erforderlich — auf einem UniFi NAS praktisch nicht umsetzbar |
| **nur rsync/rclone** | Maximal transparent, aber ohne Versionierung, Deduplizierung und Zielverschlüsselung; für Postgres-Dumps und Git-Mirrors untauglich |
| **Eigenes Format** | Nicht ernsthaft erwogen. Ein Backup-Format, das nur ein Tool lesen kann, ist ein Risiko, kein Feature |

## Entscheidung

**restic** ist die Standard-Engine. **rsync** wird als gleichberechtigter zweiter Modus (`MIRROR`) angeboten, pro Ziel wählbar.

Der Mirror-Modus ist kein Kompromiss, sondern deckt einen echten Anwendungsfall ab: die unverschlüsselte, unversionierte 1:1-Kopie auf der USB-Platte, die man ohne jedes Werkzeug im Dateimanager öffnen kann.

## Konsequenzen

- Die Engine-Abstraktion muss zwei grundverschiedene Modelle tragen: snapshot-basiert (restic) und zustands-spiegelnd (rsync). Retention, Restore und Snapshot-Browser gibt es nur im restic-Modus; die UI muss das je Ziel deutlich machen, statt Funktionen anzubieten, die dort nicht greifen.
- Das restic-Repository-Passwort wird zum kritischsten Secret im System — ohne es sind die Backups verloren. Muss im Konfig-Export und im Restore-Runbook stehen.
- Ein restic-Repository verträgt keine parallelen `prune`-Läufe. Die Nebenläufigkeitssteuerung braucht eine Sperre je Ziel.
