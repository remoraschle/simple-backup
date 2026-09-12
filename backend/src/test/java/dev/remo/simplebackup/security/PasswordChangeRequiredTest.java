package dev.remo.simplebackup.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.remo.simplebackup.IntegrationTestBase;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Der erzwungene Passwortwechsel muss im Backend greifen.
 *
 * <p>Im Frontend allein waere er wirkungslos: Das einmalig protokollierte Startpasswort
 * reichte sonst aus, um die API mit einem einzigen {@code curl}-Aufruf vollstaendig zu
 * benutzen.
 */
@AutoConfigureMockMvc
class PasswordChangeRequiredTest extends IntegrationTestBase {

    private static final String START_PASSWORD = "erstpasswort-vom-erststart";
    private static final String NEW_PASSWORD = "ein-neues-langes-passwort";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    private String username;

    @BeforeEach
    void createUserWithInitialPassword() {
        username = "wechsler-" + UUID.randomUUID();
        userService.create(username, START_PASSWORD, UserRole.ADMIN, true);
    }

    @Test
    @DisplayName("Mit dem Erstpasswort ist die API gesperrt, bis es gewechselt wurde")
    void apiIsLockedUntilThePasswordWasChanged() throws Exception {
        MockHttpSession session = login(START_PASSWORD);

        mockMvc.perform(get("/api/plans").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Passwortwechsel erforderlich"));

        // Der Weg zum Wechsel selbst muss offen bleiben, sonst sperrte man sich aus.
        mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        mockMvc.perform(post("/api/auth/password").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s"}
                                """.formatted(START_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/plans").session(login(NEW_PASSWORD)))
                .andExpect(status().isOk());
    }

    private MockHttpSession login(String password) throws Exception {
        HttpSession session = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        assertThat(session).isNotNull();
        return (MockHttpSession) session;
    }
}
