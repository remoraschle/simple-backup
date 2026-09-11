# ADR-0005: Angular Material und Tailwind als Frontend-Stack

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Die Oberfläche besteht im Kern aus sechs Bausteinen: Dashboard mit Kacheln und Sparklines, Lauf-Historie als Tabelle mit aufklappbaren Schritten, Snapshot-Browser (Baum mit Spalten, pro Ebene nachgeladen), Plan-Editor mit je Quelltyp wechselnden Feldern, Live-Log-Ansicht und eine Zeitleiste der Lauf-Schritte.

Zur Wahl standen Angular Material, PrimeNG und ein rein headless Ansatz auf CDK-Basis.

## Vergleich

**Komponentenabdeckung** (geprüft am 2026-09-11, PrimeNG 22.1.1, Angular Material 22.1.6):

| Baustein | Angular Material | PrimeNG |
|---|---|---|
| Daten-Tabelle | `MatTable` — Sortierung und Paginierung; Filter, Spaltenbreiten, Export selbst zu bauen | `p-table` — Filter, Frozen Columns, Resize, Reorder, Row-Expansion, CSV-Export eingebaut |
| Baum mit Spalten | **fehlt** — `MatTree` kennt keine Spalten | `p-treeTable` mit Lazy Loading |
| Zeitleiste | fehlt | `p-timeline` |
| Log-Ansicht | fehlt | `p-terminal` |
| Charts | fehlt | `p-chart` (Chart.js) |
| Barrierefreiheit | durchgängig, CDK-a11y als Fundament | je nach Komponente unterschiedlich |

**Release-Gleichlauf mit Angular** — Abstand zwischen Angular-Major und passendem Bibliotheks-Release:

| Angular | Material | PrimeNG |
|---|---|---|
| 20.0.0 (2025-05-28) | gleicher Tag | 2025-07-16 — **49 Tage** |
| 21.0.0 (2025-11-19) | gleicher Tag | 2025-12-04 — **15 Tage** |
| 22.0.0 (2026-06-03) | gleicher Tag | 2026-07-15 — **42 Tage** |

Material erscheint am selben Tag wie das Framework, weil es dasselbe Team baut. Beide unterstützen aktuell Angular 22.

Paketgrößen (entpackt, npm) liegen bei 13,4 MB für PrimeNG gegenüber 7,3 MB für Material plus 3,4 MB CDK. Beide sind tree-shakable, die ausgelieferte Bundle-Größe hängt an der tatsächlichen Nutzung — als Entscheidungskriterium taugt die Zahl kaum.

## Entscheidung

**Angular Material + CDK, ergänzt um Tailwind CSS 4** für Layout und Abstände. Fehlende Komponenten — allen voran die TreeTable für den Snapshot-Browser — werden auf CDK-Primitiven selbst gebaut.

Ausschlaggebend waren die langfristigen Eigenschaften: Release-Gleichlauf mit Angular, durchgängige Barrierefreiheit und eine API, die zwischen Majors stabil bleibt. Das Projekt soll jahrelang laufen; sechs Wochen Wartezeit bei jedem Angular-Update sind dabei unangenehmer als eine einmalig selbst gebaute Komponente.

## Konsequenzen

- **Die TreeTable ist echte Arbeit**, nicht Konfiguration: `cdk-tree` plus `cdk-virtual-scroll-viewport`, mit Spalten, Lazy Loading pro Ebene, Auswahl und Tastaturbedienung. In M4 als eigenständige Aufgabe eingeplant.
- **Die Lauf-Historie** braucht eine eigene Filterleiste und Zeilenaufklappung über `MatTable`.
- **Charts** entstehen als Inline-SVG. Für Sparklines und einen Speicher-Trend rechtfertigt nichts eine zusätzliche Abhängigkeit.
- **Die Grenze zwischen Material und Tailwind muss diszipliniert bleiben:** Tailwind nur auf eigenen Elementen, Material-Komponenten ausschließlich über Material-3-Tokens (`--mat-*`) gestalten. Utility-Klassen auf Material-Interna brechen beim nächsten Update.
- **Tailwinds Preflight** setzt Rahmen und Hintergründe zurück und kollidiert an Rändern mit Material. Die Layer-Reihenfolge wird einmal explizit festgelegt: Preflight, dann Material, dann Utilities.
- **Eine Farbquelle.** Tailwind leitet seine Farben aus denselben CSS-Custom-Properties ab wie das Material-Theme. Zwei getrennte Paletten driften auseinander, besonders im Dark Mode.
- Wird der Eigenbau-Aufwand im Frontend später zum Engpass, bleibt PrimeNG nachrüstbar — die Umstellung träfe dann aber jede Komponente.
