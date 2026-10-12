package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.support.TestAccounts.PASSWORD;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountManagementApiTests {

    static final String EMAIL = "moi@exemple.fr";
    static final String NEW_PASSWORD = "nouveau-secret";
    static final String PATTERN = """
            { "title": "Chat", "steps": [ { "title": "Tête", "rows": [ { "number": "1", "value": "6ms" } ] } ] }""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountSessions accountSessions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset(jdbc);
    }

    static String credentials(String password) {
        return """
                { "email": "%s", "password": "%s" }""".formatted(EMAIL, password);
    }

    static String passwordChange(String current, String next) {
        return """
                { "currentPassword": "%s", "newPassword": "%s" }""".formatted(current, next);
    }

    Browser registered() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    Browser loggedIn(String password) throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(password)))
                .andExpect(status().isOk());
        return browser;
    }

    int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void changePasswordSwitchesCredentials() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isUnauthorized());
        loggedIn(NEW_PASSWORD);
    }

    @Test
    void changePasswordWithWrongCurrentPasswordIsBadRequest() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange("mauvais-mot-de-passe", NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("currentPassword")));
        browser.perform(get("/api/me")).andExpect(status().isOk());

        loggedIn(PASSWORD);
    }

    @Test
    void changePasswordValidatesNewPassword() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, "court")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("newPassword")));
        browser.perform(get("/api/me")).andExpect(status().isOk());
        loggedIn(PASSWORD);
    }

    @Test
    void changePasswordEndsOtherSessionsButKeepsCurrentOne() throws Exception {
        Browser current = registered();
        Browser other = loggedIn(PASSWORD);

        current.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        other.perform(get("/api/me")).andExpect(status().isUnauthorized());
        current.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void endingOtherSessionsTakesPartInTheCallersTransaction() throws Exception {
        registered();
        loggedIn(PASSWORD);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountSessions.invalidateAllExcept(EMAIL, "none");
            status.setRollbackOnly();
        });

        assertThat(count("spring_session")).isEqualTo(2);
    }

    @Test
    void deleteAccountWithWrongPasswordIsBadRequest() throws Exception {
        Browser browser = registered();

        browser.perform(delete("/api/me").contentType(APPLICATION_JSON)
                        .content("{ \"password\": \"mauvais-mot-de-passe\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
        browser.perform(get("/api/me")).andExpect(status().isOk());

        assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void deleteAccountRemovesEverythingAndEndsAllSessions() throws Exception {
        Browser browser = registered();
        Browser other = loggedIn(PASSWORD);
        String location = browser.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(PATTERN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        browser.perform(put(location + "/progress").contentType(APPLICATION_JSON)
                        .content("{ \"status\": \"IN_PROGRESS\", \"stepIndex\": 0, \"rowIndex\": 0 }"))
                .andExpect(status().isOk());

        browser.perform(delete("/api/me").contentType(APPLICATION_JSON)
                        .content("{ \"password\": \"%s\" }".formatted(PASSWORD)))
                .andExpect(status().isNoContent())
                // the browser is told to drop its session cookie
                .andExpect(cookie().maxAge("SESSION", 0));

        browser.perform(get("/api/me")).andExpect(status().isUnauthorized());
        other.perform(get("/api/me")).andExpect(status().isUnauthorized());
        assertThat(count("account")).isZero();
        assertThat(count("pattern")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("pattern_row")).isZero();
        assertThat(count("pattern_progress")).isZero();
        assertThat(count("spring_session")).isZero();
    }
}
