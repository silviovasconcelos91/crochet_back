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
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The counters outlive each test: every test uses its own client address and emails. */
@SpringBootTest(properties = {
        "crochet.login-limit.max-failures-per-email=2",
        "crochet.login-limit.email-window=15m",
        "crochet.login-limit.max-requests-per-ip=4",
        "crochet.login-limit.ip-window=1m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginRateLimitApiTests {

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

    ResultActions send(String path, String ip, String email, String password) throws Exception {
        return new Browser(mvc).fetchCsrf()
                .perform(post(path).contentType(APPLICATION_JSON).content(credentials(email, password))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }));
    }

    @Test
    void emailIsLockedAfterRepeatedFailuresEvenWithTheRightPassword() throws Exception {
        String ip = "203.0.113.1";
        send("/api/auth/register", ip, "moi@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", ip, "moi@exemple.fr", "mauvais-1").andExpect(status().isUnauthorized());
        send("/api/auth/login", "203.0.113.2", "MOI@exemple.fr", "mauvais-2").andExpect(status().isUnauthorized());

        send("/api/auth/login", "203.0.113.3", "moi@exemple.fr", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Too many attempts, try again later"))
                // 15 min from the first failure, rounded up to whole seconds
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"));
    }

    @Test
    void unknownEmailsAreLockedLikeExistingOnes() throws Exception {
        send("/api/auth/login", "203.0.113.11", "inconnu@exemple.fr", PASSWORD).andExpect(status().isUnauthorized());
        send("/api/auth/login", "203.0.113.12", "inconnu@exemple.fr", PASSWORD).andExpect(status().isUnauthorized());

        send("/api/auth/login", "203.0.113.13", "inconnu@exemple.fr", PASSWORD)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginClearsFailures() throws Exception {
        send("/api/auth/register", "203.0.113.21", "succes@exemple.fr", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", "203.0.113.22", "succes@exemple.fr", "mauvais").andExpect(status().isUnauthorized());
        send("/api/auth/login", "203.0.113.23", "succes@exemple.fr", PASSWORD).andExpect(status().isOk());
        send("/api/auth/login", "203.0.113.24", "succes@exemple.fr", "mauvais").andExpect(status().isUnauthorized());

        send("/api/auth/login", "203.0.113.25", "succes@exemple.fr", PASSWORD).andExpect(status().isOk());
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
}
