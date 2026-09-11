# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- Bauen
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

# Erst nur die Build-Beschreibung kopieren: Solange sich die Abhaengigkeiten nicht aendern,
# bleibt diese Schicht im Cache und der Build laedt sie nicht erneut herunter.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline

COPY backend/src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

# ---------------------------------------------------------------- Laufen
FROM eclipse-temurin:25-jre

# curl allein fuer den Healthcheck: Das JRE-Image bringt kein Werkzeug mit, das HTTP sprechen
# kann, und ein Healthcheck, der die Anwendung selbst startet, pruefte nichts.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivilegiert. Das Backend fuehrt keine Backup-Werkzeuge selbst aus -- das erledigen
# Runner-Container -- und braucht deshalb keinerlei erhoehte Rechte.
RUN groupadd --system --gid 1000 simplebackup \
 && useradd --system --uid 1000 --gid simplebackup --create-home simplebackup

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

# Arbeitsverzeichnisse. Im Betrieb werden hier Volumes eingehaengt; die Verzeichnisse
# existieren trotzdem, damit ein Start ohne Volumes nicht an fehlenden Pfaden scheitert.
RUN mkdir -p /var/lib/simple-backup/staging /var/lib/simple-backup/logs \
 && chown -R simplebackup:simplebackup /var/lib/simple-backup

USER simplebackup

EXPOSE 8081

# Die Bereitschaftspruefung von Spring Boot: liefert erst dann OK, wenn auch die
# Datenbankverbindung steht.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
