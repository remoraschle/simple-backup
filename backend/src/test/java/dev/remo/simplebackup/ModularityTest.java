package dev.remo.simplebackup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Setzt die Modulgrenzen durch, statt sie nur zu dokumentieren.
 *
 * <p>Greift ein Modul auf eine Klasse zu, die in einem anderen Modul nicht zur oeffentlichen
 * Schnittstelle gehoert, scheitert dieser Test. Damit bleibt der Monolith modular, ohne dass
 * es auf Disziplin bei jedem einzelnen Import ankommt.
 */
class ModularityTest {

    @Test
    @DisplayName("Module halten ihre Grenzen ein")
    void verifiesModuleBoundaries() {
        ApplicationModules.of(SimpleBackupApplication.class).verify();
    }
}
