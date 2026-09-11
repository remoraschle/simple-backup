package dev.remo.simplebackup.shared;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Entfernt Geheimnisse aus Text, bevor er in einer Logdatei, einer gespeicherten
 * Kommandozeile oder einer Fehlermeldung landet.
 *
 * <p>Zwei Wege, weil keiner allein reicht:
 * <ul>
 *   <li><b>Angemeldete Werte</b> -- was {@code CredentialService#reveal} herausgibt, meldet
 *       sich hier an und wird danach exakt herausgefiltert. Das greift auch bei Werten, die
 *       keinem bekannten Muster folgen, etwa einem selbstgewaehlten Datenbankpasswort.</li>
 *   <li><b>Muster</b> -- fuer alles, was nie durch unsere Hand ging: ein Token in der
 *       Ausgabe eines fremden Werkzeugs, eine URL mit eingebettetem Passwort in einer
 *       Fehlermeldung von rclone.</li>
 * </ul>
 *
 * <p>Abwaegung: Angemeldete Werte liegen im Speicher, solange sie angemeldet sind. Das ist
 * der Preis dafuer, sie zuverlaessig aus Logs fernhalten zu koennen. Werte unter
 * {@value #MINIMUM_LENGTH} Zeichen werden nicht angenommen -- sie wuerden zu viel harmlosen
 * Text mitschwaerzen und die Logs unlesbar machen.
 */
@Component
public class SecretRedactor {

    public static final String PLACEHOLDER = "***";
    private static final int MINIMUM_LENGTH = 8;

    /**
     * Reihenfolge zaehlt: Spezifische Muster zuerst, damit sie greifen, bevor eine
     * allgemeinere Regel den Treffer verschluckt.
     */
    private static final List<Pattern> PATTERNS = List.of(
            // Private Schluessel als Ganzes, ueber Zeilengrenzen hinweg.
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", Pattern.DOTALL),
            // GitHub-Tokens in allen gaengigen Praefixen.
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{16,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            // AWS-Zugriffsschluessel.
            Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}"),
            // Zugangsdaten in einer URL: scheme://benutzer:passwort@host
            Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^:/@\\s]+:)([^@\\s]+)(@)"),
            // Schluessel-Wert-Paare, wie sie in Kommandozeilen und Konfigurationen vorkommen.
            Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key)\\b\\s*[=:]\\s*(\"[^\"]*\"|'[^']*'|\\S+)"));

    private final Set<String> registeredValues = ConcurrentHashMap.newKeySet();

    /** Meldet einen Klartextwert an, der ab sofort aus jedem Text entfernt wird. */
    public void register(String secret) {
        if (secret != null && secret.length() >= MINIMUM_LENGTH) {
            registeredValues.add(secret);
        }
    }

    /** Meldet einen Wert wieder ab, etwa nachdem ein Zugang geloescht wurde. */
    public void unregister(String secret) {
        if (secret != null) {
            registeredValues.remove(secret);
        }
    }

    /** Entfernt alle bekannten Geheimnisse aus dem Text. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;

        // Exakte Treffer zuerst: Sie sind sicher richtig, waehrend Muster raten.
        for (String secret : registeredValues) {
            if (result.contains(secret)) {
                result = result.replace(secret, PLACEHOLDER);
            }
        }

        for (Pattern pattern : PATTERNS) {
            result = switch (pattern.matcher(result).groupCount()) {
                // Muster mit Gruppen erhalten den Kontext und schwaerzen nur den Wert,
                // damit im Log noch erkennbar bleibt, worum es ging.
                case 3 -> pattern.matcher(result).replaceAll("$1" + PLACEHOLDER + "$3");
                case 2 -> pattern.matcher(result).replaceAll("$1=" + PLACEHOLDER);
                default -> pattern.matcher(result).replaceAll(PLACEHOLDER);
            };
        }
        return result;
    }

    /** Bequemlichkeit fuer Kommandozeilen, die als Argumentliste vorliegen. */
    public List<String> redact(List<String> arguments) {
        return arguments.stream().map(this::redact).toList();
    }
}
