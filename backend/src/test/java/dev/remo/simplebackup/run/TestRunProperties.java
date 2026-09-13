package dev.remo.simplebackup.run;

import java.time.Duration;

/**
 * Baut {@link RunProperties} fuer Tests.
 *
 * <p>An einer Stelle, weil der Datensatz mit jeder neuen Quelle waechst: Ohne diesen Helfer
 * muesste jeder Test angefasst werden, sobald ein Feld dazukommt -- und ein Test, der wegen
 * einer Erweiterung nicht mehr uebersetzt, sagt nichts ueber die Erweiterung aus.
 *
 * <p>Alle Felder kennen einen Standard; {@code null} bedeutet also "wie im Betrieb".
 */
final class TestRunProperties {

    private String logDirectory;
    private String stagingDirectory;
    private Duration pruneTimeout;
    private Duration watchdogGrace;
    private Duration acquireTimeout;
    private String githubApiUrl;

    private TestRunProperties() {
    }

    static TestRunProperties defaults() {
        return new TestRunProperties();
    }

    TestRunProperties stagingDirectory(String directory) {
        this.stagingDirectory = directory;
        return this;
    }

    /** Kurze Zeitlimits: Ein Test soll nicht stundenlang auf ein Aufraeumen warten. */
    TestRunProperties pruneTimeout(Duration timeout) {
        this.pruneTimeout = timeout;
        return this;
    }

    TestRunProperties watchdogGrace(Duration grace) {
        this.watchdogGrace = grace;
        return this;
    }

    TestRunProperties acquireTimeout(Duration timeout) {
        this.acquireTimeout = timeout;
        return this;
    }

    TestRunProperties githubApiUrl(String url) {
        this.githubApiUrl = url;
        return this;
    }

    RunProperties build() {
        return new RunProperties(logDirectory, null, 2, null, stagingDirectory, pruneTimeout,
                null, watchdogGrace, acquireTimeout, null, githubApiUrl);
    }
}
