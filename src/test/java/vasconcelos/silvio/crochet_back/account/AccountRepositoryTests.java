package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountRepositoryTests {

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountService accountService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        // other classes sharing this context commit rows; the truncate is rolled back with the test
        TestDatabase.reset(jdbc);
    }

    @Test
    void savesAndFindsAccountByEmail() {
        Account saved = accounts.saveAndFlush(new Account("moi", "moi@exemple.fr", "hash"));

        Account found = accounts.findByEmail("moi@exemple.fr").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(accounts.findByUsernameKey("moi").orElseThrow().getId()).isEqualTo(saved.getId());
        assertThat(found.getPasswordHash()).isEqualTo("hash");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(accounts.existsByEmail("moi@exemple.fr")).isTrue();
        assertThat(accounts.existsByEmail("autre@exemple.fr")).isFalse();
    }

    @Test
    void rejectsDuplicateEmail() {
        accounts.saveAndFlush(new Account("moi", "moi@exemple.fr", "hash"));

        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("autre", "moi@exemple.fr", "hash2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateUsernameKey() {
        accounts.saveAndFlush(new Account("Moi", "moi@exemple.fr", "hash"));

        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("MOI", "autre@exemple.fr", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonLowercaseEmail() {
        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("moi", "Moi@Exemple.fr", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void registerReportsOnlyTheEmailUniqueConstraintAsAlreadyUsed() {
        // breaks the lowercase CHECK, not the unique constraint: not an "email already used" conflict
        assertThatThrownBy(() -> accountService.register("moi", "Moi@Exemple.fr", "motdepasse-secret"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
