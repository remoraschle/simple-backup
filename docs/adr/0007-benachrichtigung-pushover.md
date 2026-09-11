# ADR-0007: Pushover als primärer Alarmkanal

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Bei Fehlern soll benachrichtigt werden. Zur Wahl standen E-Mail über SMTP, ntfy, ein generischer Webhook sowie Chat-Dienste wie Telegram oder Slack.

## Entscheidung

**Pushover** als primärer Kanal, ergänzt um einen **generischen Webhook**.

## Begründung

Der entscheidende Unterschied zu allen Alternativen ist die **Emergency-Priorität** (`priority=2`): Pushover wiederholt eine Meldung in einem konfigurierbaren Intervall, bis sie auf dem Gerät quittiert wird. Für ein fehlgeschlagenes Backup ist genau das die richtige Zustellsemantik — eine Benachrichtigung, die man morgens verschlafen wegwischt, hat ihren Zweck verfehlt, und eine E-Mail im Posteingang neben vierzig anderen erst recht.

Die API ist ein einzelner HTTP-POST; es braucht keinen SMTP-Server, keinen Bot und keine Registrierung bei einem Chat-Dienst.

Der generische Webhook kommt mit, weil die Kanal-Abstraktion ohnehin entsteht und ein JSON-POST an eine frei wählbare URL danach kaum Aufwand ist. Er hält Anbindungen an n8n, Home Assistant oder Gotify offen, ohne dass dafür Code geändert werden muss.

## Konsequenzen

- Zwei Secrets kommen dazu: App-Token und User- bzw. Group-Key.
- Prioritätszuordnung: `RUN_FAILED`, `VERIFY_FAILED` und `RETENTION_WOULD_DELETE_ALL` auf `2` (Quittierung erforderlich); `RUN_PARTIAL`, `PLAN_OVERDUE`, `TARGET_UNREACHABLE` und `STORAGE_LOW` auf `1`; Erfolgs- und Erholungsmeldungen auf `-1`.
- Das Monatskontingent von 10.000 Nachrichten wird über den Antwort-Header `X-Limit-App-Remaining` ausgewertet und als Metrik geführt. Ein aufgebrauchtes Kontingent legt sonst stillschweigend die gesamte Alarmierung lahm.
- Quittierte Emergency-Meldungen werden nicht erneut eskaliert, solange sich der Zustand nicht ändert — sonst wird die Funktion zur Belästigung und der Nutzer schaltet sie ab.
- **Ein einzelner Kanal ist selbst ein Single Point of Failure.** Abgefedert wird das durch den externen Dead-Man-Switch, der unabhängig von diesem Tool und von Pushover alarmiert. SMTP bleibt als zweiter Kanal nachrüstbar; die Architektur hält den Platz frei.
- Pushover ist ein kommerzieller Dienst mit einmaligen Kosten je Plattform und übermittelt Statusdaten an einen Dritten. Die Meldungen enthalten deshalb Planname und Fehlerklasse, aber keine Pfade, Hostnamen oder Zugangsdaten.
