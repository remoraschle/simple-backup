package dev.remo.simplebackup.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeverityTest {

    @Test
    @DisplayName("Ein Kanal bekommt alles ab seiner Mindeststufe")
    void reachesMinimum() {
        assertThat(Severity.CRITICAL.reaches(Severity.WARNING)).isTrue();
        assertThat(Severity.WARNING.reaches(Severity.WARNING)).isTrue();
        assertThat(Severity.INFO.reaches(Severity.WARNING)).isFalse();
    }

    @Test
    @DisplayName("Wer den Erfolgsfall sehen will, stellt die Stufe auf INFO")
    void infoChannelGetsEverything() {
        for (Severity severity : Severity.values()) {
            assertThat(severity.reaches(Severity.INFO)).isTrue();
        }
    }
}
