# ADR-0006: Maven als Build-Tool

- **Status:** Angenommen
- **Datum:** 2026-09-11

## Kontext

Für ein Spring-Boot-Backend stehen Maven und Gradle zur Wahl. Empfohlen war Gradle mit Kotlin DSL wegen typsicherer Buildskripte und schnellerer inkrementeller Builds.

## Entscheidung

**Maven**, mit eingechecktem Wrapper (`mvnw`), damit CI und Entwicklungsrechner dieselbe Version verwenden.

## Begründung

Maven ist deklarativ und ohne Einarbeitung lesbar. Ein `pom.xml` verhält sich so, wie es aussieht — bei Gradle-Skripten ist das nicht immer der Fall, weil sie Programme sind. Für ein Projekt mit genau einem Backend-Modul spielt Gradle seine Stärken (komplexe Multi-Modul-Builds, aufwendige eigene Tasks) ohnehin kaum aus, während der Nachteil — ein Build, den nur versteht, wer Gradle kennt — bestehen bleibt.

Der Geschwindigkeitsvorteil bei inkrementellen Builds ist real, fällt bei dieser Projektgröße aber nicht ins Gewicht. Spring Boot wird zudem primär gegen Maven dokumentiert; Beispiele aus der Dokumentation lassen sich unverändert übernehmen.

## Konsequenzen

- Frontend-Build läuft getrennt über npm und wird im Docker-Mehrstufen-Build eingebunden, statt ihn über ein Maven-Plugin in den Java-Build zu ziehen. Das hält beide Builds unabhängig und lokal einzeln ausführbar.
- Testcontainers, Flyway und das OpenAPI-Plugin werden über Maven-Plugins eingebunden.
- Abhängigkeitsversionen zentral in `<properties>`, Spring-Boot-Parent als BOM.
