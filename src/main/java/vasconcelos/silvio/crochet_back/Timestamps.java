package vasconcelos.silvio.crochet_back;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

    private Timestamps() {
    }

    /** Current instant at microsecond precision, as stored by PostgreSQL timestamptz. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
