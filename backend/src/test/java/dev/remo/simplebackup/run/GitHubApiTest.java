package dev.remo.simplebackup.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitHubApiTest {

    private FakeGitHub github;
    private GitHubApi api;

    @BeforeEach
    void setUp() {
        github = new FakeGitHub();
        api = new GitHubApi(HttpClient.newHttpClient(), new ObjectMapper(),
                TestRunProperties.defaults().acquireTimeout(Duration.ofMinutes(1))
                        .githubApiUrl(github.baseUrl()).build());
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    private static String repository(String name, boolean fork) {
        return """
                {"name":"%s","full_name":"remo/%s","clone_url":"https://github.com/remo/%s.git",
                 "fork":%s,"archived":false}""".formatted(name, name, name, fork);
    }

    @Test
    @DisplayName("Findet die Repositories einer Organisation")
    void findsRepositoriesOfAnOrganisation() {
        github.respond("/orgs/remo", "{\"login\":\"remo\"}")
                .respond("/orgs/remo/repos?per_page=100&page=1",
                        "[" + repository("werkzeug", false) + "]");

        var repositories = api.listRepositories("remo", "geheim", false);

        assertThat(repositories).singleElement().satisfies(repository -> {
            assertThat(repository.name()).isEqualTo("werkzeug");
            assertThat(repository.cloneUrl()).endsWith("werkzeug.git");
        });
    }

    @Test
    @DisplayName("Faellt auf den Benutzer zurueck, wenn es keine Organisation ist")
    void fallsBackToUser() {
        // Von aussen sieht man einem Namen nicht an, was er ist.
        github.respond("/users/remo/repos?per_page=100&page=1", "[" + repository("privat", false) + "]");

        assertThat(api.listRepositories("remo", "geheim", false))
                .extracting(GitHubApi.Repository::name).containsExactly("privat");
    }

    @Test
    @DisplayName("Laesst geforkte Repositories weg, wenn sie nicht gewuenscht sind")
    void skipsForks() {
        // Ihr Inhalt liegt anderswo ohnehin.
        github.respond("/users/remo/repos?per_page=100&page=1",
                "[" + repository("eigen", false) + "," + repository("fremd", true) + "]");

        assertThat(api.listRepositories("remo", "geheim", false))
                .extracting(GitHubApi.Repository::name).containsExactly("eigen");
        assertThat(api.listRepositories("remo", "geheim", true))
                .extracting(GitHubApi.Repository::name).containsExactly("eigen", "fremd");
    }

    @Test
    @DisplayName("Blaettert bis zum Ende")
    void readsAllPages() {
        // Wer dreissig Repositories hat und nur die ersten dreissig sichert, merkt beim
        // einunddreissigsten nichts -- bis er es braucht.
        var firstPage = new StringBuilder("[");
        for (int index = 0; index < 100; index++) {
            firstPage.append(index == 0 ? "" : ",").append(repository("repo" + index, false));
        }
        firstPage.append("]");

        github.respond("/users/remo/repos?per_page=100&page=1", firstPage.toString())
                .respond("/users/remo/repos?per_page=100&page=2", "[" + repository("letztes", false) + "]");

        assertThat(api.listRepositories("remo", "geheim", false)).hasSize(101);
    }

    @Test
    @DisplayName("Der Token geht als Kopffeld mit")
    void sendsTheToken() {
        github.respond("/users/remo/repos?per_page=100&page=1", "[]");

        api.listRepositories("remo", "ghp_geheim", false);

        assertThat(github.authorizations()).allMatch(value -> value.equals("Bearer ghp_geheim"));
    }

    @Test
    @DisplayName("Metadaten enthalten Issues und Releases")
    void collectsMetadata() {
        // Sie liegen nicht im Git-Repository und waeren nach einem Verlust des Kontos weg.
        github.respond("/repos/remo/werkzeug/issues?state=all&per_page=100",
                        "[{\"number\":1,\"title\":\"Kaputt\"}]")
                .respond("/repos/remo/werkzeug/releases?per_page=100",
                        "[{\"tag_name\":\"v1.0\"}]");

        String metadata = api.metadata("remo/werkzeug", "geheim");

        assertThat(metadata).contains("\"repository\":\"remo/werkzeug\"")
                .contains("Kaputt").contains("v1.0").contains("fetchedAt");
    }

    @Test
    @DisplayName("Ein Fehler der API bleibt nicht unbemerkt")
    void reportsApiErrors() {
        // Sonst sicherte ein Plan stillschweigend null Repositories.
        assertThatThrownBy(() -> api.metadata("remo/gibtesnicht", "geheim"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404");
    }
}
