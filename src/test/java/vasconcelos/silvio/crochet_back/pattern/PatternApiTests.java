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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.VALID_STEPS;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.titled;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PatternApiTests {

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
    void createReturns201WithLocationAndBody() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(CHAT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/patterns/\\d+")))
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    void createResponseTimestampsEqualThoseReadBack() throws Exception {
        MvcResult created = mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(CHAT))
                .andExpect(status().isCreated())
                .andReturn();
        String posted = created.getResponse().getContentAsString();
        String read = mvc.perform(get(created.getResponse().getHeader("Location")))
                .andReturn().getResponse().getContentAsString();

        assertThat(instant(posted, "$.createdAt")).isEqualTo(instant(read, "$.createdAt"));
        assertThat(instant(posted, "$.updatedAt")).isEqualTo(instant(read, "$.updatedAt"));
    }

    @Test
    void getReturnsCreatedPatternWithStepAndRowOrder() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.description").value("Petit chat"))
                .andExpect(jsonPath("$.hookSizeMm").value(3.5))
                .andExpect(jsonPath("$.yarn").value("Coton 8/4"))
                .andExpect(jsonPath("$.sourceUrl").value("https://example.com/chat"))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/chat.jpg"))
                .andExpect(jsonPath("$.steps[0].title").value("Tête"))
                .andExpect(jsonPath("$.steps[0].rows[0].number").value("1"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value("6ms dans cercle magique (6)"))
                .andExpect(jsonPath("$.steps[0].rows[1].value").value("6aug (12)"))
                .andExpect(jsonPath("$.steps[0].rows[2].value").value("3ms, [1ms, 4aug]x6 (34)"))
                .andExpect(jsonPath("$.steps[1].title").value("Corps"))
                .andExpect(jsonPath("$.steps[1].rows[0].number").value("1-10"));
    }

    @Test
    void listReturnsSummariesSortedByTitle() throws Exception {
        create(mvc, titled("Zèbre"));
        create(mvc, CHAT);

        mvc.perform(get("/api/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$[0].stepCount").value(2))
                .andExpect(jsonPath("$[0].imageUrl").value("https://example.com/chat.jpg"))
                .andExpect(jsonPath("$[0].steps").doesNotExist())
                .andExpect(jsonPath("$[1].title").value("Zèbre"))
                .andExpect(jsonPath("$[1].stepCount").value(1));
    }

    @Test
    void listIsEmptyArrayWhenNoPattern() throws Exception {
        mvc.perform(get("/api/patterns"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void unknownIdIsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/patterns/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Pattern 999 not found"));
    }

    @Test
    void nonNumericIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/patterns/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    static Stream<Arguments> invalidRequests() {
        String t = "\"title\": \"T\",";
        String longText201 = "a".repeat(201);
        String longText501 = "a".repeat(501);
        return Stream.of(
                Arguments.of(withSteps("\"title\": \" \",", VALID_STEPS), "title"),
                Arguments.of(withSteps("", VALID_STEPS), "title"),
                Arguments.of(withSteps("\"title\": \"" + longText201 + "\",", VALID_STEPS), "title"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 0,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": -1,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 123.4,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 3.555,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"yarn\": \"" + longText201 + "\",", VALID_STEPS), "yarn"),
                Arguments.of(withSteps(t + "\"sourceUrl\": \"" + longText501 + "\",", VALID_STEPS), "sourceUrl"),
                Arguments.of(withSteps(t + "\"imageUrl\": \"" + longText501 + "\",", VALID_STEPS), "imageUrl"),
                Arguments.of("{" + t.substring(0, t.length() - 1) + "}", "steps"),
                Arguments.of(withSteps(t, "[]"), "steps"),
                Arguments.of(withSteps(t, """
                        [ { "title": "", "rows": [ { "number": "1", "value": "6ms" } ] } ]"""), "steps[0].title"),
                Arguments.of(withSteps(t, """
                        [ { "title": "%s", "rows": [ { "number": "1", "value": "6ms" } ] } ]"""
                        .formatted(longText201)), "steps[0].title"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [] } ]"""), "steps[0].rows"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A" } ]"""), "steps[0].rows"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": " ", "value": "6ms" } ] } ]"""),
                        "steps[0].rows[0].number"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "%s", "value": "6ms" } ] } ]"""
                        .formatted("1".repeat(21))), "steps[0].rows[0].number"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "1", "value": "" } ] } ]"""),
                        "steps[0].rows[0].value"),
                // NUL is rejected by PostgreSQL text columns; it must be a 400, not a 500
                Arguments.of(withSteps("\"title\": \"T\\u0000\",", VALID_STEPS), "title"),
                Arguments.of(withSteps(t + "\"description\": \"a\\u0000b\",", VALID_STEPS), "description"),
                Arguments.of(withSteps(t + "\"yarn\": \"\\u0000\",", VALID_STEPS), "yarn"),
                Arguments.of(withSteps(t + "\"sourceUrl\": \"\\u0000\",", VALID_STEPS), "sourceUrl"),
                Arguments.of(withSteps(t + "\"imageUrl\": \"\\u0000\",", VALID_STEPS), "imageUrl"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A\\u0000", "rows": [ { "number": "1", "value": "6ms" } ] } ]"""),
                        "steps[0].title"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "1\\u0000", "value": "6ms" } ] } ]"""),
                        "steps[0].rows[0].number"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "1", "value": "6ms\\u0000" } ] } ]"""),
                        "steps[0].rows[0].value"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestIsBadRequestNamingTheField(String json, String field) throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)))
                .andExpect(jsonPath("$.errors[0].message").value(notNullValue()));

        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nullStepOrRowElementIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(withSteps("\"title\": \"T\",", "[null]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(endsWith("steps[0]"))));

        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(withSteps("\"title\": \"T\",", """
                                [ { "title": "A", "rows": [null] } ]""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(endsWith("steps[0].rows[0]"))));
    }

    @Test
    void accentsAndLongRowValuesRoundTrip() throws Exception {
        String longValue = "1ms, 1aug, ".repeat(60).trim();
        String json = withSteps("\"title\": \"Tête de chat à crocheter\",", """
                [ { "title": "Étape maille serrée", "rows": [ { "number": "1", "value": "%s" } ] } ]"""
                .formatted(longValue));

        String location = create(mvc, json);

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tête de chat à crocheter"))
                .andExpect(jsonPath("$.steps[0].title").value("Étape maille serrée"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value(longValue));
    }

    private static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }
}
