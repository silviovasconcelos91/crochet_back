package vasconcelos.silvio.crochet_back.account;

import lombok.Getter;

import java.time.Duration;

@Getter
public class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super("Too many attempts, try again later");
        this.retryAfter = retryAfter;
    }

    /** Value of the Retry-After header: whole seconds, rounded up so that a client never retries early. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        return retryAfter.toNanosPart() > 0 ? seconds + 1 : seconds;
    }
}
