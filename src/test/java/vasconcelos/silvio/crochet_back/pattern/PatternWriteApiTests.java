package vasconcelos.silvio.crochet_back.pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PatternWriteApiTests {

    private static final String ONE_STEP = withSteps("\"title\": \"Chat v2\", \"yarn\": \"Laine\",", """
            [ { "title": "Unique", "rows": [
                { "number": "1", "value": "8ms (8)" },
                { "number": "2", "value": "8aug (16)" } ] } ]""");

    private static final String THREE_STEPS = withSteps("\"title\": \"Chat v3\",", """
            [ { "title": "S1", "rows": [ { "number": "1", "value": "a" } ] },
              { "title": "S2", "rows": [ { "number": "1", "value": "b" } ] },
              { "title": "S3", "rows": [ { "number": "1", "value": "c" } ] } ]""");

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        mvc = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "moi"));
    }

    @Test
    void createIsPrivateByDefault() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(get(location)).andExpect(jsonPath("$.visibility").value("PRIVATE"));
        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$[0].visibility").value("PRIVATE"));
    }

    @Test
    void createCanBePublic() throws Exception {
        String location = create(mvc, PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",",
                PatternJson.VALID_STEPS));

        mvc.perform(get(location)).andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void replaceWithoutVisibilityMakesThePatternPrivate() throws Exception {
        String location = create(mvc, PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",",
                PatternJson.VALID_STEPS));

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(PatternJson.titled("Chat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void unknownVisibilityIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"SECRET\",",
                                PatternJson.VALID_STEPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("visibility"));
    }

    @Test
    void putReplacesFieldsAndTree() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat v2"));

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat v2"))
                .andExpect(jsonPath("$.yarn").value("Laine"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.hookSizeMm").doesNotExist())
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].title").value("Unique"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value("8ms (8)"))
                .andExpect(jsonPath("$.steps[0].rows[1].value").value("8aug (16)"));

        assertThat(count("step")).isEqualTo(1);
        assertThat(count("pattern_row")).isEqualTo(2);
    }

    @Test
    void putWithMoreStepsThanBeforeSucceeds() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(THREE_STEPS))
                .andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.steps[0].title").value("S1"))
                .andExpect(jsonPath("$.steps[1].title").value("S2"))
                .andExpect(jsonPath("$.steps[2].title").value("S3"));
        assertThat(count("step")).isEqualTo(3);
        assertThat(count("pattern_row")).isEqualTo(3);
    }

    @Test
    void putChangingOnlyRowsBumpsUpdatedAt() throws Exception {
        String location = create(mvc, CHAT);
        String before = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();

        String sameFieldsOtherRow = CHAT.replace("34ms (34)", "34ms en spirale (34)");
        String after = mvc.perform(put(location).contentType(APPLICATION_JSON).content(sameFieldsOtherRow))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(instant(after, "$.createdAt")).isEqualTo(instant(before, "$.createdAt"));
        assertThat(instant(after, "$.updatedAt")).isAfter(instant(before, "$.updatedAt"));
        assertThat((String) JsonPath.read(after, "$.steps[1].rows[0].value")).isEqualTo("34ms en spirale (34)");
    }

    @Test
    void putResponseUpdatedAtEqualsValueReadBack() throws Exception {
        String location = create(mvc, CHAT);

        String putBody = mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String read = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();

        assertThat(instant(putBody, "$.updatedAt")).isEqualTo(instant(read, "$.updatedAt"));
    }

    @Test
    void invalidPutLeavesPatternUnchanged() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(withSteps("\"title\": \"\",", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));

        mvc.perform(get(location))
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    void putUnknownIdIsNotFound() throws Exception {
        mvc.perform(put("/api/patterns/999").contentType(APPLICATION_JSON).content(ONE_STEP))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void deleteRemovesPatternStepsAndRows() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(delete(location)).andExpect(status().isNoContent());

        mvc.perform(get(location)).andExpect(status().isNotFound());
        assertThat(count("pattern")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("pattern_row")).isZero();
    }

    @Test
    void concurrentDeleteAndWritesNeverFail() throws Exception {
        for (int round = 0; round < 10; round++) {
            String location = create(mvc, CHAT);
            mvc.perform(put(location + "/progress").contentType(APPLICATION_JSON)
                            .content("{ \"status\": \"IN_PROGRESS\", \"stepIndex\": 0, \"rowIndex\": 0 }"))
                    .andExpect(status().isOk());
            List<MockHttpServletRequestBuilder> requests = List.of(
                    delete(location),
                    put(location).contentType(APPLICATION_JSON).content(ONE_STEP),
                    delete(location + "/progress"));
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
                List<Future<Integer>> statuses = new ArrayList<>();
                for (MockHttpServletRequestBuilder request : requests) {
                    statuses.add(pool.submit(() -> {
                        start.await();
                        return mvc.perform(request).andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                for (Future<Integer> status : statuses) {
                    // each request either wins or finds the pattern already gone
                    assertThat(status.get()).isIn(200, 204, 404);
                }
            }
        }
    }

    @Test
    void deleteUnknownIdIsNotFound() throws Exception {
        mvc.perform(delete("/api/patterns/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }
}
