package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTests {

    static final String PASSWORD = "motdepasse-secret";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset(jdbc);
    }

    static String credentials(String email, String password) {
        return """
                { "email": "%s", "password": "%s" }""".formatted(email, password);
    }

    Browser registered(String email) throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    @Test
    void registerCreatesAccountAndOpensSession() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();

        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("Moi@Exemple.fr", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        browser.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"));
    }

    @Test
    void csrfCookieIsSecureByDefault() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header).startsWith("XSRF-TOKEN=").contains("Secure"));
    }

    @Test
    void sessionIsStoredInDatabaseWithHttpOnlyLaxSecureCookie() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();

        MvcResult result = browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header)
                        .startsWith("SESSION=")
                        .contains("HttpOnly")
                        .contains("Secure")
                        .contains("SameSite=Lax"));
        Integer stored = jdbc.queryForObject(
                "select count(*) from spring_session where principal_name = 'moi@exemple.fr'", Integer.class);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void registerWithTakenEmailIgnoringCaseIsConflict() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("  MOI@exemple.FR ", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Email already used"));
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of(credentials("pas-un-email", PASSWORD), "email"),
                Arguments.of(credentials(" ", PASSWORD), "email"),
                Arguments.of(credentials("a".repeat(250) + "@x.fr", PASSWORD), "email"),
                Arguments.of(credentials("moi@exemple.fr", "1234567"), "password"),
                Arguments.of(credentials("moi@exemple.fr", "é".repeat(37)), "password"),
                Arguments.of("{ \"email\": \"moi@exemple.fr\" }", "password"));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationIsBadRequestNamingTheField(String json, String field) throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));

        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
    }

    @Test
    void registerAcceptsPasswordOfExactly72Bytes() throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "é".repeat(36))))
                .andExpect(status().isCreated());
    }

    @Test
    void registerWhileLoggedInSwitchesAccount() throws Exception {
        Browser browser = registered("premier@exemple.fr");

        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("second@exemple.fr", PASSWORD)))
                .andExpect(status().isCreated());

        browser.perform(get("/api/me")).andExpect(jsonPath("$.email").value("second@exemple.fr"));
    }

    @Test
    void loginOpensSession() throws Exception {
        registered("moi@exemple.fr");
        Browser browser = new Browser(mvc).fetchCsrf();

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"));

        browser.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void loginNormalizesEmail() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("  Moi@EXEMPLE.fr ", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void loginFailureIsIdenticalForWrongPasswordAndUnknownEmail() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "mauvais-mot-de-passe")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("inconnu@exemple.fr", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void loginWithOverlongPasswordIsUnauthorized() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "é".repeat(60))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithMissingFieldIsBadRequest() throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{ \"email\": \"moi@exemple.fr\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void loginChangesSessionId() throws Exception {
        Browser browser = registered("moi@exemple.fr");
        Cookie before = browser.cookie("SESSION");

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk());

        assertThat(browser.cookie("SESSION").getValue()).isNotEqualTo(before.getValue());
        mvc.perform(get("/api/me").cookie(before)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginIssuesFreshCsrfToken() throws Exception {
        registered("moi@exemple.fr");
        Browser browser = new Browser(mvc).fetchCsrf();
        Cookie before = browser.cookie("XSRF-TOKEN");

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk());

        assertThat(browser.cookie("XSRF-TOKEN").getValue()).isNotEqualTo(before.getValue());
        // a token captured before login no longer passes CSRF
        mvc.perform(post("/api/auth/logout")
                        .cookie(browser.cookie("SESSION"), browser.cookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", before.getValue()))
                .andExpect(status().isForbidden());
        browser.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void registerIssuesFreshCsrfToken() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        Cookie before = browser.cookie("XSRF-TOKEN");

        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isCreated());

        assertThat(browser.cookie("XSRF-TOKEN").getValue()).isNotEqualTo(before.getValue());
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void protectedEndpointsWithoutSessionAreUnauthorized() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
        mvc.perform(get("/api/patterns")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesSessionServerSide() throws Exception {
        Browser browser = registered("moi@exemple.fr");
        Cookie session = browser.cookie("SESSION");

        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }

    @Test
    void loginAfterLogoutWorksWithoutFetchingCsrfAgain() throws Exception {
        Browser browser = registered("moi@exemple.fr");

        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk());
        browser.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void maximumLengthEmailCanRegisterAndLogIn() throws Exception {
        String label = "a".repeat(60);
        String email = "moi@" + label + "." + label + "." + label + "." + label + "." + "x".repeat(6);
        assertThat(email).hasSize(254);

        Browser browser = registered(email);
        browser.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isOk());
    }
}
