package dev.remo.simplebackup.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.remo.simplebackup.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthenticationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Ohne Anmeldung liefert die API 401 statt einer Weiterleitung")
    void unauthenticatedRequestsAreRejected() throws Exception {
        // Eine Single-Page-Anwendung kann mit einer Weiterleitung auf ein Loginformular
        // nichts anfangen; sie braucht einen klaren Statuscode.
        mockMvc.perform(get("/api/credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Der Sitzungsstatus ist ohne Anmeldung abrufbar")
    void sessionEndpointIsPublic() throws Exception {
        // Damit das Frontend beim Start pruefen kann, ob noch eine Sitzung besteht,
        // ohne dafuer absichtlich einen Fehler zu provozieren.
        mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    @DisplayName("Falsche Zugangsdaten verraten nicht, was falsch war")
    void failedLoginIsUnspecific() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "gibtesnicht")
                        .param("password", "falsch")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Anmeldung fehlgeschlagen"));
    }

    @Test
    @DisplayName("Veraendernde Anfragen ohne CSRF-Token werden abgewiesen")
    @WithMockUser(roles = "ADMIN")
    void rejectsRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/credentials").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER darf lesen, aber nichts veraendern")
    @WithMockUser(roles = "VIEWER")
    void viewerMayReadButNotWrite() throws Exception {
        mockMvc.perform(get("/api/credentials"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/credentials")
                        .contentType("application/json")
                        .content("{}")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }
}
