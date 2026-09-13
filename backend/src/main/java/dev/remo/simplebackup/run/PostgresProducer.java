package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.SourceType;
import dev.remo.simplebackup.engine.BackupExecutor;
import dev.remo.simplebackup.engine.ExecutionException;
import dev.remo.simplebackup.engine.ExecutionRequest;
import dev.remo.simplebackup.engine.ExecutionResult;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.secret.CredentialService;
import dev.remo.simplebackup.shared.SecretRedactor;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Erzeugt einen Dump einer PostgreSQL-Datenbank.
 *
 * <p><b>Warum ein Dump und keine Dateikopie:</b> Die Dateien einer laufenden Datenbank zu
 * sichern ergibt einen Stand mitten in einer Transaktion -- einspielen laesst er sich nicht,
 * und das merkt man erst, wenn man ihn braucht.
 *
 * <p><b>Warum ein eigenes Image je Hauptversion:</b> {@code pg_dump} weigert sich, eine
 * neuere Datenbank zu lesen, als es selbst kennt. Ein Werkzeug, das seine eigene Version
 * mitbringt, sichert frueher oder spaeter nichts mehr -- und meldet es nicht.
 *
 * <p>Das Passwort geht ueber eine Datei, nie ueber die Umgebung oder die Kommandozeile: Beides
 * waere ueber {@code docker inspect} beziehungsweise die Prozessliste lesbar.
 */
@Component
class PostgresProducer implements SourceProducer {

    private static final Logger log = LoggerFactory.getLogger(PostgresProducer.class);

    /** Dateiname des Passworts im Runner, im Format von {@code .pgpass}. */
    private static final String PASSWORD_FILE = "pgpass";

    private final BackupExecutor executor;
    private final CredentialService credentials;
    private final ResticTargets targets;
    private final SecretRedactor redactor;
    private final RunProperties properties;

    PostgresProducer(BackupExecutor executor, CredentialService credentials, ResticTargets targets,
            SecretRedactor redactor, RunProperties properties) {
        this.executor = executor;
        this.credentials = credentials;
        this.targets = targets;
        this.redactor = redactor;
        this.properties = properties;
    }

    @Override
    public SourceType type() {
        return SourceType.POSTGRES;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {

        SourceConfig.Postgres source = (SourceConfig.Postgres) plan.source();
        VolumeMount staging = targets.translate(stagingDirectory, false);

        String password = credentials.reveal(source.credentialId());
        String image = properties.postgresImage().formatted(source.majorVersion());

        List<String> databases = source.databases().isEmpty()
                ? listDatabases(plan, source, image, staging, password, listener)
                : source.databases();

        for (String database : databases) {
            dump(plan, source, database, image, staging, password, listener);
        }
        if (source.includeGlobals()) {
            dumpGlobals(plan, source, image, staging, password, listener);
        }

        return new PreparedSource(List.of(staging.target()), List.of(staging), List.of(),
                stagingDirectory);
    }

    /**
     * Sichert eine Datenbank im eigenen Format von PostgreSQL.
     *
     * <p>Nicht als reines SQL: Das eigene Format laesst sich selektiv einspielen, parallel
     * zurueckschreiben und vor allem pruefen, ohne es auszufuehren -- siehe
     * {@link #verify}.
     */
    private void dump(ExecutablePlan plan, SourceConfig.Postgres source, String database,
            String image, VolumeMount staging, String password, RunProgressListener listener) {

        String file = staging.target() + "/" + database + ".dump";

        List<String> command = new ArrayList<>(List.of("pg_dump",
                "--format=custom",
                "--compress=zstd",
                "--no-password",
                "--file=" + file,
                "--host=" + source.host(),
                "--port=" + source.port(),
                "--username=" + source.username(),
                "--dbname=" + database));

        run(plan, StepKind.ACQUIRE, "Dump von " + database, command, image, source, staging,
                password, listener);

        verify(plan, database, file, image, staging, password, listener);
    }

    /**
     * Rollen und Tablespaces.
     *
     * <p>Ohne sie laesst sich ein Dump zwar einspielen, aber niemand darf hinterher darauf
     * zugreifen -- und im Ernstfall sucht man den Fehler zuerst woanders.
     */
    private void dumpGlobals(ExecutablePlan plan, SourceConfig.Postgres source, String image,
            VolumeMount staging, String password, RunProgressListener listener) {

        List<String> command = List.of("pg_dumpall",
                "--globals-only",
                "--no-password",
                "--file=" + staging.target() + "/globals.sql",
                "--host=" + source.host(),
                "--port=" + source.port(),
                "--username=" + source.username());

        run(plan, StepKind.ACQUIRE, "Rollen und Tablespaces", command, image, source, staging,
                password, listener);
    }

    /**
     * Prueft den Dump, bevor er gesichert wird.
     *
     * <p>{@code pg_restore --list} liest das Inhaltsverzeichnis, ohne etwas einzuspielen.
     * Kommt es damit nicht zurecht, ist die Datei unbrauchbar -- und das soll jetzt auffallen
     * und nicht in der Nacht, in der man sie braucht.
     */
    private void verify(ExecutablePlan plan, String database, String file, String image,
            VolumeMount staging, String password, RunProgressListener listener) {

        run(plan, StepKind.VERIFY, "Dump von " + database + " prüfen",
                List.of("pg_restore", "--list", file), image, null, staging, password, listener);
    }

    /** @return die Ausgabezeilen des Schritts, fuer Kommandos, deren Ergebnis gebraucht wird */
    private List<String> run(ExecutablePlan plan, StepKind kind, String description,
            List<String> command, String image, SourceConfig.Postgres source, VolumeMount staging,
            String password, RunProgressListener listener) {

        var builder = ExecutionRequest.builder(image, command.toArray(String[]::new))
                .executionId(UUID.randomUUID().toString())
                .timeout(properties.acquireTimeout())
                .mount(staging)
                .label("simple-backup.plan-id", plan.planId().toString());

        if (source != null) {
            builder.env("PGPASSFILE", ExecutionRequest.SECRETS_DIRECTORY + "/" + PASSWORD_FILE)
                    .secretFile(PASSWORD_FILE, pgpass(source, password));
        }

        ExecutionRequest request = builder.build();
        UUID stepId = listener.stepStarted(kind, null, description, image,
                redactor.redact(request.command()));

        List<String> output = new ArrayList<>();

        try {
            var running = executor.start(request, line -> {
                output.add(line);
                listener.logLine(line);
            });
            ExecutionResult result = running.awaitCompletion(properties.acquireTimeout());

            listener.stepFinished(stepId, result.isSuccess() ? StepStatus.SUCCESS : StepStatus.FAILED,
                    result.exitCode(), result.isSuccess() ? description : result.lastError());

            if (!result.isSuccess()) {
                // Ohne Dump gibt es nichts zu sichern. Weiterzumachen hiesse, ein leeres
                // Verzeichnis zu sichern und es Erfolg zu nennen.
                throw new IllegalStateException("%s fehlgeschlagen: %s"
                        .formatted(description, result.lastError()));
            }
            log.debug("{} abgeschlossen", description);
            return output;

        } catch (ExecutionException e) {
            String message = redactor.redact(String.valueOf(e.getMessage()));
            listener.stepFinished(stepId, StepStatus.FAILED, null, message);
            throw new IllegalStateException("%s fehlgeschlagen: %s".formatted(description, message), e);
        }
    }

    /**
     * Der Inhalt von {@code .pgpass}: ein Datensatz je Zeile, Doppelpunkte als Trenner.
     *
     * <p>Der Stern bei der Datenbank ist Absicht -- derselbe Zugang gilt fuer alle Dumps
     * dieses Laufs, und pg_dumpall verbindet sich zusaetzlich mit {@code postgres}.
     */
    private static String pgpass(SourceConfig.Postgres source, String password) {
        return "%s:%d:*:%s:%s%n".formatted(source.host(), source.port(), source.username(), password);
    }

    /**
     * Alle Datenbanken des Servers, ausser den Vorlagen.
     *
     * <p>Gefragt wird der Server selbst und nicht eine gepflegte Liste: Eine neue Datenbank
     * waere sonst so lange ungesichert, bis jemand daran denkt -- und daran denkt niemand.
     */
    private List<String> listDatabases(ExecutablePlan plan, SourceConfig.Postgres source,
            String image, VolumeMount staging, String password, RunProgressListener listener) {

        List<String> command = List.of("psql",
                "--no-password",
                "--tuples-only",
                "--no-align",
                "--host=" + source.host(),
                "--port=" + source.port(),
                "--username=" + source.username(),
                "--dbname=postgres",
                "--command=SELECT datname FROM pg_database "
                        + "WHERE NOT datistemplate AND datallowconn ORDER BY datname");

        List<String> output = run(plan, StepKind.ACQUIRE, "Datenbanken ermitteln", command, image,
                source, staging, password, listener);

        List<String> databases = output.stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();

        if (databases.isEmpty()) {
            throw new IllegalStateException("Der Server meldet keine einzige Datenbank");
        }
        return databases;
    }
}
