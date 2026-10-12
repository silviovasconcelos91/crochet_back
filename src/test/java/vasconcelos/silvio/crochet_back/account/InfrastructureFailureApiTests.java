package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.support.TestAccounts.PASSWORD;

/** What the API does when the database fails in the middle of an authentication flow. */
@SpringBootTest(properties = "crochet.login-limit.max-failures-per-email-and-client=2")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InfrastructureFailureApiTests {

    static final String USERNAME = "moi";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AccountUserDetailsService userDetailsService;

    @MockitoSpyBean
    AccountSessions accountSessions;

    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset(jdbc);
    }

    static String registration(String password) {
        return """
                { "username": "%s", "email": "moi@exemple.fr", "password": "%s" }""".formatted(USERNAME, password);
    }

    static String credentials(String password) {
        return """
                { "username": "%s", "password": "%s" }""".formatted(USERNAME, password);
    }

    Browser registered() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(registration(PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    @Test
    void registerHashesThePasswordOnlyOnce() throws Exception {
        registered();

        verify(passwordEncoder).encode(PASSWORD);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void databaseFailureDuringLoginIsLoggedServerError(CapturedOutput output) throws Exception {
        registered();
        doThrow(new DataAccessResourceFailureException("database is down"))
                .when(userDetailsService).loadUserByUsername(any());

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Internal error"));

        assertThat(output).contains("database is down");
    }

    @Test
    void serverFaultsDoNotUseTheLoginQuota() throws Exception {
        registered();
        doThrow(new DataAccessResourceFailureException("database is down"))
                .doThrow(new DataAccessResourceFailureException("database is down"))
                .doThrow(new DataAccessResourceFailureException("database is down"))
                .doCallRealMethod()
                .when(userDetailsService).loadUserByUsername(any());
        for (int i = 0; i < 3; i++) {
            new Browser(mvc).fetchCsrf()
                    .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                    .andExpect(status().isInternalServerError());
        }

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void passwordIsKeptWhenOtherSessionsCannotBeEnded() throws Exception {
        Browser browser = registered();
        doThrow(new DataAccessResourceFailureException("database is down"))
                .when(accountSessions).invalidateAllExcept(any(), any());

        assertThatThrownBy(() -> browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "%s", "newPassword": "nouveau-secret" }""".formatted(PASSWORD))))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isOk());
    }
}
