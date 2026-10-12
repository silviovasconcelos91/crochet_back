package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsTests {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromConfiguredOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/patterns/1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void locationHeaderIsExposedToConfiguredOrigin() throws Exception {
        mvc.perform(get("/api/auth/csrf").header("Origin", "http://localhost:5173"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Location")))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightWithCsrfHeaderAllowsCredentials() throws Exception {
        mvc.perform(options("/api/patterns/1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-xsrf-token")));
    }

    @Test
    void preflightFromOtherOriginIsRejected() throws Exception {
        mvc.perform(options("/api/patterns")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
