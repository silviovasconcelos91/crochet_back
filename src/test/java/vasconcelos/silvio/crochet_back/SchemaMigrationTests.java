package vasconcelos.silvio.crochet_back;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
