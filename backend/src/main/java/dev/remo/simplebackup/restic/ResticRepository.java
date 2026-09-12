package dev.remo.simplebackup.restic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein restic-Repository: Adresse, Umgebung und benoetigte Geheimnisdateien.
 *
 * <p>Alle Zugangsdaten gehen ueber Dateien, nie ueber Umgebungsvariablen. Die waeren
 * dauerhaft ueber {@code docker inspect} lesbar -- restic und die darunterliegende
 * S3-Bibliothek unterstuetzen Dateiverweise nativ, es gibt also keinen Grund dafuer.
 *
 * @param url         Repository-Adresse in der Schreibweise von restic
 * @param environment Umgebungsvariablen, ausschliesslich Dateiverweise und Schalter
 * @param secretFiles Dateiname unterhalb von {@code /run/secrets} auf Klartext
 */
public record ResticRepository(String url, Map<String, String> environment, Map<String, String> secretFiles) {

    /** Dateiname des Repository-Passworts im Runner. */
    public static final String PASSWORD_FILE = "restic-password";

    /** Dateiname der S3-Zugangsdaten im Runner. */
    public static final String AWS_CREDENTIALS_FILE = "aws-credentials";

    private static final String SECRETS_DIRECTORY = "/run/secrets";

    public ResticRepository {
        environment = Map.copyOf(environment);
        secretFiles = Map.copyOf(secretFiles);
    }

    /**
     * Ein Repository auf einer lokalen Platte oder einem eingehaengten Netzlaufwerk.
     *
     * @param containerPath Pfad aus Sicht des Runners
     */
    public static ResticRepository localPath(String containerPath, String repositoryPassword) {
        requireAbsolutePath(containerPath);
        return new ResticRepository(containerPath, baseEnvironment(), passwordOnly(repositoryPassword));
    }

    /**
     * Ein Repository in einem S3-Bucket oder einem S3-kompatiblen Dienst.
     *
     * <p>Die Zugangsdaten landen in einer Datei im Format der AWS-Kommandozeile, auf die
     * {@code AWS_SHARED_CREDENTIALS_FILE} zeigt -- statt in
     * {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}, die jeder mit Docker-Zugriff
     * auslesen koennte.
     *
     * @param endpoint   Adresse des Dienstes, etwa {@code https://s3.eu-central-1.amazonaws.com}
     *                   oder {@code http://minio:9000}
     * @param bucket     Name des Buckets
     * @param prefix     Pfad im Bucket, darf leer sein
     */
    public static ResticRepository s3(String endpoint, String bucket, String prefix,
            String accessKeyId, String secretAccessKey, String repositoryPassword) {

        requireText(endpoint, "endpoint");
        requireText(bucket, "bucket");
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Die Adresse muss mit http:// oder https:// beginnen: " + endpoint);
        }
        if (bucket.contains("/")) {
            throw new IllegalArgumentException("Der Bucket-Name darf keinen Schraegstrich enthalten: " + bucket);
        }

        String location = bucket + normalizePrefix(prefix);
        String url = "s3:" + stripTrailingSlash(endpoint) + "/" + location;

        var environment = baseEnvironment();
        environment.put("AWS_SHARED_CREDENTIALS_FILE", SECRETS_DIRECTORY + "/" + AWS_CREDENTIALS_FILE);

        var secrets = passwordOnly(repositoryPassword);
        secrets.put(AWS_CREDENTIALS_FILE, awsCredentialsFile(accessKeyId, secretAccessKey));

        return new ResticRepository(url, environment, secrets);
    }

    /** Format, das die AWS-Kommandozeile und die S3-Bibliothek von restic lesen. */
    private static String awsCredentialsFile(String accessKeyId, String secretAccessKey) {
        requireText(accessKeyId, "accessKeyId");
        requireText(secretAccessKey, "secretAccessKey");
        return """
                [default]
                aws_access_key_id = %s
                aws_secret_access_key = %s
                """.formatted(accessKeyId, secretAccessKey);
    }

    private static LinkedHashMap<String, String> baseEnvironment() {
        var environment = new LinkedHashMap<String, String>();
        environment.put("RESTIC_PASSWORD_FILE", SECRETS_DIRECTORY + "/" + PASSWORD_FILE);
        // Ohne Anfrage nach einem Passwort auf der Konsole -- im Container gibt es niemanden,
        // der antworten koennte, und der Lauf bliebe bis zum Zeitlimit haengen.
        environment.put("RESTIC_CACHE_DIR", "/tmp/restic-cache");
        return environment;
    }

    private static LinkedHashMap<String, String> passwordOnly(String repositoryPassword) {
        requireText(repositoryPassword, "repositoryPassword");
        var secrets = new LinkedHashMap<String, String>();
        secrets.put(PASSWORD_FILE, repositoryPassword);
        return secrets;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.equals("/")) {
            return "";
        }
        String trimmed = stripTrailingSlash(prefix.strip());
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void requireAbsolutePath(String path) {
        requireText(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Der Pfad muss absolut sein: " + path);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
    }

    /** Ohne Umgebung und Geheimnisse -- diese Darstellung landet in Logs. */
    @Override
    public String toString() {
        return "ResticRepository[url=%s]".formatted(url);
    }
}
