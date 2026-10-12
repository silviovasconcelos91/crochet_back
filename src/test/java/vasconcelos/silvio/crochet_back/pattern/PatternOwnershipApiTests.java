package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.titled;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PatternOwnershipApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    AccountPrincipal alice;
    AccountPrincipal bob;
    MockMvc aliceMvc;
    MockMvc bobMvc;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.create(accounts, passwordEncoder, "alice");
        aliceMvc = TestAccounts.mockMvcAs(context, alice);
        bob = TestAccounts.create(accounts, passwordEncoder, "bob");
        bobMvc = TestAccounts.mockMvcAs(context, bob);
    }

    @Test
    void createdPatternBelongsToTheLoggedInAccount() throws Exception {
        String location = create(aliceMvc, CHAT);
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        Long ownerId = jdbc.queryForObject("select owner_id from pattern where id = ?", Long.class, id);
        assertThat(ownerId).isEqualTo(alice.id());
    }

    @Test
    void listOnlyContainsOwnPatterns() throws Exception {
        create(aliceMvc, CHAT);
        create(bobMvc, titled("Bonnet"));

        aliceMvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Amigurumi Chat"));
        bobMvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Bonnet"));
    }

    @Test
    void otherAccountsPatternLooksExactlyLikeAMissingOne() throws Exception {
        String location = create(aliceMvc, CHAT);
        String id = location.substring(location.lastIndexOf('/') + 1);
        String missingId = String.valueOf(Long.parseLong(id) + 1000);
        String missing = bobMvc.perform(get("/api/patterns/" + missingId)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        bobMvc.perform(get(location))
                .andExpect(status().isNotFound())
                .andExpect(content().json(missing.replace(missingId, id), JsonCompareMode.STRICT));
    }

    @Test
    void otherAccountsPatternIsNotFoundForEveryOperation() throws Exception {
        String location = create(aliceMvc, CHAT);

        bobMvc.perform(get(location)).andExpect(status().isNotFound());
        bobMvc.perform(put(location).contentType(APPLICATION_JSON).content(titled("Volé")))
                .andExpect(status().isNotFound());
        bobMvc.perform(delete(location)).andExpect(status().isNotFound());

        aliceMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"));
    }
}
