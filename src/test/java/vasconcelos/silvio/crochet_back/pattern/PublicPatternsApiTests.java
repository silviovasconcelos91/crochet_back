package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.VALID_STEPS;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PublicPatternsApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc alice;
    MockMvc bob;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "Alice"));
        bob = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "Bob"));
    }

    static String pattern(String title, String visibility) {
        return withSteps("\"title\": \"%s\", \"visibility\": \"%s\",".formatted(title, visibility), VALID_STEPS);
    }

    static String progress(int stepIndex, int rowIndex) {
        return """
                { "status": "IN_PROGRESS", "stepIndex": %d, "rowIndex": %d }""".formatted(stepIndex, rowIndex);
    }

    int progressRows() {
        return jdbc.queryForObject("select count(*) from pattern_progress", Integer.class);
    }

    @Test
    void otherAccountReadsAPublicPatternWithItsAuthor() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));

        bob.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.author.username").value("Alice"))
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.progress").doesNotExist());
        alice.perform(get(location)).andExpect(jsonPath("$.mine").value(true));
    }

    @Test
    void otherAccountCannotWriteAPublicPattern() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        String id = location.substring(location.lastIndexOf('/') + 1);

        bob.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Volé", "PRIVATE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Pattern " + id + " belongs to another account"));
        bob.perform(delete(location)).andExpect(status().isForbidden());

        alice.perform(get(location))
                .andExpect(jsonPath("$.title").value("Chat"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void privatePatternStaysInvisibleToOthers() throws Exception {
        String location = create(alice, pattern("Secret", "PRIVATE"));

        bob.perform(get(location)).andExpect(status().isNotFound());
        bob.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Volé", "PUBLIC")))
                .andExpect(status().isNotFound());
        bob.perform(delete(location)).andExpect(status().isNotFound());
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void progressOnAnotherAccountsPrivatePatternIsNotFoundAndWritesNothing() throws Exception {
        String location = create(alice, pattern("Secret", "PRIVATE"));

        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isNotFound());
        bob.perform(delete(location + "/progress")).andExpect(status().isNotFound());

        assertThat(progressRows()).isZero();
    }

    @Test
    void catalogueListsEveryPublicPatternSortedWithMyProgress() throws Exception {
        create(alice, pattern("zèbre", "PUBLIC"));
        create(alice, pattern("Caché", "PRIVATE"));
        String chat = create(bob, pattern("Chat", "PUBLIC"));
        alice.perform(put(chat + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        alice.perform(get("/api/patterns/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Chat"))
                .andExpect(jsonPath("$[0].author.username").value("Bob"))
                .andExpect(jsonPath("$[0].mine").value(false))
                .andExpect(jsonPath("$[0].progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[1].title").value("zèbre"))
                .andExpect(jsonPath("$[1].mine").value(true))
                .andExpect(jsonPath("$[1].progress").doesNotExist());
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$[0].progress").doesNotExist());
    }

    @Test
    void myListStaysMinePublicOrNot() throws Exception {
        create(alice, pattern("Public", "PUBLIC"));
        create(alice, pattern("Privé", "PRIVATE"));
        create(bob, pattern("De Bob", "PUBLIC"));

        alice.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[0].author.username").value("Alice"));
    }

    @Test
    void twoAccountsFollowOnePatternIndependently() throws Exception {
        String location = create(alice, withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",", """
                [ { "title": "A", "rows": [ { "number": "1", "value": "6ms" }, { "number": "2", "value": "6aug" } ] } ]"""));

        alice.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 1)))
                .andExpect(status().isOk());
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        alice.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(1));
        bob.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(0));

        bob.perform(delete(location + "/progress")).andExpect(status().isNoContent());
        alice.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(1));
        bob.perform(get(location)).andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void authorFollowsARename() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        jdbc.update("update account set username = 'Alicia', username_key = 'alicia' where username_key = 'alice'");

        bob.perform(get(location)).andExpect(jsonPath("$.author.username").value("Alicia"));
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$[0].author.username").value("Alicia"));
    }

    @Test
    void deletingAFollowersAccountKeepsThePattern() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        jdbc.update("delete from account where username_key = 'bob'");

        assertThat(progressRows()).isZero();
        alice.perform(get(location)).andExpect(status().isOk());
    }
}
