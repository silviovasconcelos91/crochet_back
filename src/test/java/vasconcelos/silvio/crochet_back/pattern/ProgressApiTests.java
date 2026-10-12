package vasconcelos.silvio.crochet_back.pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProgressApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    String location;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.reset(jdbc);
        mvc = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "moi@exemple.fr"));
        location = create(mvc, CHAT); // steps: "Tête" (3 rows), "Corps" (1 row)
    }

    static String progress(String status, Object stepIndex, Object rowIndex) {
        return """
                { "status": %s, "stepIndex": %s, "rowIndex": %s }"""
                .formatted(status == null ? "null" : "\"" + status + "\"", stepIndex, rowIndex);
    }

    ResultActions putProgress(MockMvc client, String json) throws Exception {
        return client.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(json));
    }

    String putProgressOk(String json) throws Exception {
        return putProgress(mvc, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }

    @Test
    void startCreatesProgressAtGivenPosition() throws Exception {
        putProgress(mvc, progress("IN_PROGRESS", 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.stepIndex").value(0))
                .andExpect(jsonPath("$.rowIndex").value(0))
                .andExpect(jsonPath("$.startedAt").value(notNullValue()))
                .andExpect(jsonPath("$.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.finishedAt").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isEqualTo(1);
    }

    @Test
    void advancingKeepsStartedAtAndBumpsUpdatedAt() throws Exception {
        String started = putProgressOk(progress("IN_PROGRESS", 0, 0));

        String advanced = putProgressOk(progress("IN_PROGRESS", 0, 2));

        assertThat((Integer) JsonPath.read(advanced, "$.rowIndex")).isEqualTo(2);
        assertThat(instant(advanced, "$.startedAt")).isEqualTo(instant(started, "$.startedAt"));
        assertThat(instant(advanced, "$.updatedAt")).isAfter(instant(started, "$.updatedAt"));
    }

    @Test
    void finishingSetsFinishedAtOnceAndReopeningClearsIt() throws Exception {
        String started = putProgressOk(progress("IN_PROGRESS", 0, 0));

        String finished = putProgressOk(progress("FINISHED", 1, 0));
        String finishedAgain = putProgressOk(progress("FINISHED", 1, 0));
        String reopened = putProgressOk(progress("IN_PROGRESS", 1, 0));

        assertThat((String) JsonPath.read(finished, "$.status")).isEqualTo("FINISHED");
        assertThat(instant(finishedAgain, "$.finishedAt")).isEqualTo(instant(finished, "$.finishedAt"));
        assertThat((String) JsonPath.read(reopened, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((Object) JsonPath.read(reopened, "$.finishedAt")).isNull();
        assertThat(instant(reopened, "$.startedAt")).isEqualTo(instant(started, "$.startedAt"));
    }

    @Test
    void abandonDeletesProgressThenReportsNotStarted() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(delete(location + "/progress")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
        mvc.perform(delete(location + "/progress"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("is not started")));
    }

    static Stream<Arguments> invalidProgress() {
        return Stream.of(
                Arguments.of(progress("IN_PROGRESS", 2, 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", -1, 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, 3), "rowIndex"),
                Arguments.of(progress("IN_PROGRESS", 1, 1), "rowIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, -1), "rowIndex"),
                Arguments.of(progress(null, 0, 0), "status"),
                Arguments.of(progress("IN_PROGRESS", "null", 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, "null"), "rowIndex"));
    }

    @ParameterizedTest
    @MethodSource("invalidProgress")
    void invalidProgressIsBadRequestNamingTheField(String json, String field) throws Exception {
        putProgress(mvc, json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
    }

    @Test
    void unknownStatusIsBadRequest() throws Exception {
        putProgress(mvc, progress("PAUSED", 0, 0))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownPatternIsNotFound() throws Exception {
        mvc.perform(put("/api/patterns/999/progress").contentType(APPLICATION_JSON)
                        .content(progress("IN_PROGRESS", 0, 0)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/patterns/999/progress")).andExpect(status().isNotFound());
    }

    @Test
    void otherAccountCannotTouchProgress() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));
        MockMvc bob = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "bob@exemple.fr"));

        putProgress(bob, progress("FINISHED", 1, 0)).andExpect(status().isNotFound());
        bob.perform(delete(location + "/progress")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from pattern_progress", String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void deletingPatternDeletesItsProgress() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(delete(location)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
    }

    static final String ONE_STEP = PatternJson.withSteps("\"title\": \"Chat v2\",", """
            [ { "title": "Unique", "rows": [
                { "number": "1", "value": "8ms (8)" },
                { "number": "2", "value": "8aug (16)" } ] } ]""");

    @Test
    void progressIsNullUntilStartedThenAppearsInDetailAndSummary() throws Exception {
        mvc.perform(get(location)).andExpect(jsonPath("$.progress").doesNotExist());
        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$[0].progress").doesNotExist());

        putProgressOk(progress("IN_PROGRESS", 1, 0));

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress.stepIndex").value(1))
                .andExpect(jsonPath("$.progress.rowIndex").value(0))
                .andExpect(jsonPath("$.progress.startedAt").value(notNullValue()));
        mvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$[0].progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].progress.stepIndex").value(1))
                .andExpect(jsonPath("$[0].progress.rowIndex").value(0));
    }

    @Test
    void replacingPatternClampsRowIntoNewStructure() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 2)); // third row of "Tête"

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP)).andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0))
                .andExpect(jsonPath("$.progress.rowIndex").value(1))
                .andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"));
    }

    @Test
    void replacingPatternClampsStepAndKeepsStatus() throws Exception {
        String finished = putProgressOk(progress("FINISHED", 1, 0)); // "Corps"

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP)).andExpect(status().isOk());

        String detail = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(detail, "$.progress.stepIndex")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(detail, "$.progress.rowIndex")).isEqualTo(0);
        assertThat((String) JsonPath.read(detail, "$.progress.status")).isEqualTo("FINISHED");
        assertThat(instant(detail, "$.progress.startedAt")).isEqualTo(instant(finished, "$.startedAt"));
    }

    @Test
    void replacingPatternKeepsProgressStillInRange() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(CHAT)).andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0))
                .andExpect(jsonPath("$.progress.rowIndex").value(1));
    }
}
