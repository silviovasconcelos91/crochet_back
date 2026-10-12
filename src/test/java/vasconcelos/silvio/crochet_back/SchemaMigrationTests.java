package vasconcelos.silvio.crochet_back;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesPatternTables() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_name in ('pattern', 'step', 'pattern_row')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("pattern", "pattern_row", "step");
    }

    @Test
    void flywayCreatesAccountAndSessionTables() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('account', 'spring_session', 'spring_session_attributes')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("account", "spring_session", "spring_session_attributes");
    }

    @Test
    void patternVisibilityIsConstrained() {
        String check = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_pattern_visibility'
                """, String.class);
        assertThat(check).contains("PRIVATE").contains("PUBLIC");
    }

    @Test
    void usernameKeyIsUnique() {
        TestDatabase.reset(jdbc); // other classes sharing this context commit rows
        jdbc.update("insert into account (email, password_hash, created_at, username, username_key) "
                + "values ('a@x.fr', 'h', now(), 'Moi', 'moi')");

        assertThatThrownBy(() -> jdbc.update("insert into account (email, password_hash, created_at, username, username_key) "
                + "values ('b@x.fr', 'h', now(), 'MOI', 'moi')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from account");
    }
}
