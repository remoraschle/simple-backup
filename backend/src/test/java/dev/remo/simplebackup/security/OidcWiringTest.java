package dev.remo.simplebackup.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.remo.simplebackup.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dass der zweite Anmeldeweg wirklich eingehaengt wird, sobald ein Anbieter konfiguriert ist.
 *
 * <p>Die Abbildung auf einen Benutzer prueft {@link OidcLoginTest}; hier geht es nur um die
 * Verdrahtung -- den Teil, der beim Ausprobieren am haeufigsten fehlt und sich als "der
 * Knopf tut nichts" aeussert.
 *
 * <p>Eingeschaltet wird derselbe Weg wie im Betrieb: ueber das Profil {@code oidc} und
 * dieselben Umgebungsvariablen. Damit ist auch die Konfigurationsdatei geprueft -- ein
 * Tippfehler darin faellt sonst erst dem Betreiber auf.
 */
@AutoConfigureMockMvc
@ActiveProfiles({"test", "oidc"})
@TestPropertySource(properties = {
        "SIMPLEBACKUP_OIDC_CLIENT_ID=simple-backup",
        "SIMPLEBACKUP_OIDC_CLIENT_SECRET=geheim",
        "simplebackup.oidc.display-name=Hausanmeldung"})
class OidcWiringTest extends IntegrationTestBase {

    /**
     * Die Adresse des Anbieters steht erst fest, wenn sein Server laeuft -- und der muss
     * laufen, bevor die Anwendung startet, denn sie fragt ihn beim Start.
     */
    @DynamicPropertySource
    static void providerAddress(DynamicPropertyRegistry registry) {
        registry.add("SIMPLEBACKUP_OIDC_ISSUER", FakeProvider::issuerUri);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Die Anmeldeseite erfaehrt, dass es einen Anbieter gibt")
    void announcesTheProvider() throws Exception {
        // Oeffentlich erreichbar: Wer die Anmeldeseite sieht, ist noch nicht angemeldet.
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oidcEnabled").value(true))
                .andExpect(jsonPath("$.displayName").value("Hausanmeldung"))
                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/oidc"));
    }

    @Test
    @DisplayName("Der Knopf fuehrt wirklich zum Anbieter")
    void redirectsToTheProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/oidc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/authorize")));
    }

    @Test
    @DisplayName("Angemeldet sein muss man trotzdem")
    void stillProtectsTheApi() throws Exception {
        // Ein zweiter Anmeldeweg ist kein offenes Tor.
        mockMvc.perform(get("/api/credentials"))
                .andExpect(status().isUnauthorized());
    }
}
