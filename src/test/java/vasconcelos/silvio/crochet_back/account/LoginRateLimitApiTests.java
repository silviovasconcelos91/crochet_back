package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.support.TestAccounts.PASSWORD;

/** The counters outlive each test: every test uses its own client addresses and emails. */
@SpringBootTest(properties = {
        "crochet.login-limit.max-failures-per-email-and-client=2",
        "crochet.login-limit.max-failures-per-email=3",
        "crochet.login-limit.email-window=15m",
        "crochet.login-limit.max-requests-per-ip=4",
        "crochet.login-limit.ip-window=1m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginRateLimitApiTests {

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

    static MockHttpServletRequestBuilder from(String ip, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    ResultActions send(String path, String ip, String email, String password) throws Exception {
        return new Browser(mvc).fetchCsrf()
                .perform(from(ip, post(path).contentType(APPLICATION_JSON).content(credentials(email, password))));
    }

    @Test
    void clientIsLockedAfterRepeatedFailuresEvenWithTheRightPassword() throws Exception {
        String ip = "203.0.113.1";
        send("/api/auth/register", ip, "moi@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", ip, "moi@exemple.fr", "mauvais-1").andExpect(status().isUnauthorized());
        send("/api/auth/login", ip, "MOI@exemple.fr", "mauvais-2").andExpect(status().isUnauthorized());

        send("/api/auth/login", ip, "moi@exemple.fr", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Too many attempts, try again later"))
                // what remains of the 15 min counted from the first attempt
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("8[6-9][0-9]|900")));
    }

    @Test
    void otherClientsCanStillLogInWhileOneIsLocked() throws Exception {
        String attacker = "203.0.113.11";
        send("/api/auth/register", "203.0.113.10", "cible@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", attacker, "cible@exemple.fr", "mauvais-1").andExpect(status().isUnauthorized());
        send("/api/auth/login", attacker, "cible@exemple.fr", "mauvais-2").andExpect(status().isUnauthorized());
        send("/api/auth/login", attacker, "cible@exemple.fr", PASSWORD).andExpect(status().isTooManyRequests());

        send("/api/auth/login", "203.0.113.12", "cible@exemple.fr", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void emailIsLockedForEveryClientAfterTooManyFailuresOverall() throws Exception {
        send("/api/auth/register", "203.0.113.20", "global@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", "203.0.113.21", "global@exemple.fr", "mauvais").andExpect(status().isUnauthorized());
        send("/api/auth/login", "203.0.113.22", "global@exemple.fr", "mauvais").andExpect(status().isUnauthorized());
        send("/api/auth/login", "203.0.113.23", "global@exemple.fr", "mauvais").andExpect(status().isUnauthorized());

        send("/api/auth/login", "203.0.113.24", "global@exemple.fr", PASSWORD)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unknownEmailsAreLockedLikeExistingOnes() throws Exception {
        String ip = "203.0.113.31";
        send("/api/auth/login", ip, "inconnu@exemple.fr", PASSWORD).andExpect(status().isUnauthorized());
        send("/api/auth/login", ip, "inconnu@exemple.fr", PASSWORD).andExpect(status().isUnauthorized());

        send("/api/auth/login", ip, "inconnu@exemple.fr", PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginClearsFailures() throws Exception {
        String ip = "203.0.113.41";
        send("/api/auth/register", "203.0.113.40", "succes@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", ip, "succes@exemple.fr", "mauvais").andExpect(status().isUnauthorized());
        send("/api/auth/login", ip, "succes@exemple.fr", PASSWORD).andExpect(status().isOk());
        send("/api/auth/login", ip, "succes@exemple.fr", "mauvais").andExpect(status().isUnauthorized());

        send("/api/auth/login", ip, "succes@exemple.fr", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void addressIsThrottledAcrossLoginAndRegister() throws Exception {
        String ip = "198.51.100.7";
        send("/api/auth/register", ip, "ip-1@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", ip, "ip-1@exemple.fr", PASSWORD).andExpect(status().isOk());
        send("/api/auth/login", ip, "ip-2@exemple.fr", PASSWORD).andExpect(status().isUnauthorized());
        send("/api/auth/register", ip, "ip-3@exemple.fr", PASSWORD).andExpect(status().isCreated());

        send("/api/auth/register", ip, "ip-4@exemple.fr", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        send("/api/auth/login", ip, "ip-1@exemple.fr", PASSWORD).andExpect(status().isTooManyRequests());
        send("/api/auth/login", "198.51.100.8", "ip-1@exemple.fr", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void passwordChecksOfTheAccountEndpointsAreThrottled() throws Exception {
        String ip = "203.0.113.51";
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(from(ip, post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("session-volee@exemple.fr", PASSWORD))))
                .andExpect(status().isCreated());
        for (int i = 0; i < 2; i++) {
            browser.perform(from(ip, put("/api/me/password").contentType(APPLICATION_JSON)
                            .content("{ \"currentPassword\": \"mauvais\", \"newPassword\": \"nouveau-secret\" }")))
                    .andExpect(status().isBadRequest());
        }

        browser.perform(from(ip, put("/api/me/password").contentType(APPLICATION_JSON)
                        .content("{ \"currentPassword\": \"%s\", \"newPassword\": \"nouveau-secret\" }"
                                .formatted(PASSWORD))))
                .andExpect(status().isTooManyRequests());
        browser.perform(from(ip, delete("/api/me").contentType(APPLICATION_JSON)
                        .content("{ \"password\": \"%s\" }".formatted(PASSWORD))))
                .andExpect(status().isTooManyRequests());
    }
}
