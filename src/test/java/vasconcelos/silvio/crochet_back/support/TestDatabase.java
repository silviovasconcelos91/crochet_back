package vasconcelos.silvio.crochet_back.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabase {

    private TestDatabase() {
    }

    /** Empties every application table and the session store. */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE account, pattern, spring_session RESTART IDENTITY CASCADE");
    }
}
