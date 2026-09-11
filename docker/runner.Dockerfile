# syntax=docker/dockerfile:1

# Werkzeugkasten fuer die eigentliche Backup-Arbeit.
#
# Das Backend fuehrt nichts davon selbst aus, sondern startet je Schritt einen Container aus
# diesem Image (siehe docs/konzept.md, Abschnitt 6). Damit sind die Werkzeuge unabhaengig
# vom Backend aktualisierbar, und ein Backend-Neustart beendet keinen laufenden Backup-Lauf.
#
# PostgreSQL-Quellen verwenden dieses Image nicht, sondern postgres:<major>-alpine, damit
# pg_dump immer mindestens so neu ist wie der jeweilige Server.
FROM alpine:3.22

RUN apk add --no-cache \
        restic \
        rsync \
        rclone \
        git \
        openssh-client \
        ca-certificates \
        zstd \
        tzdata

# Unprivilegiert. Wo ein Backup hoehere Rechte braucht -- etwa fuer Dateien, die einem
# anderen Benutzer gehoeren -- wird die passende UID beim Start des Containers gesetzt,
# statt hier pauschal als Wurzel zu laufen.
RUN addgroup -g 1000 runner && adduser -D -u 1000 -G runner runner

# Geheimnisse werden zur Laufzeit in ein tmpfs unter /run/secrets gelegt und ueber
# Dateiverweise gelesen (--password-file, PGPASSFILE, ...). Niemals als Umgebungsvariable:
# die waere dauerhaft ueber "docker inspect" lesbar.
RUN mkdir -p /run/secrets && chown runner:runner /run/secrets && chmod 700 /run/secrets

USER runner

# Bewusst kein ENTRYPOINT: Das Backend gibt die vollstaendige Argumentliste vor.
CMD ["sh"]
