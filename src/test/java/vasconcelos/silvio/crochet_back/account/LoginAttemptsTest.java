package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptsTest {

    static final String EMAIL = "moi@exemple.fr";
    static final String IP = "203.0.113.7";

    final MutableClock clock = new MutableClock();
    final LoginAttempts attempts = new LoginAttempts(
            new LoginLimitProperties(3, Duration.ofMinutes(15), 2, Duration.ofMinutes(1)), clock);

    @Test
    void emailIsBlockedOnceFailuresReachTheLimit() {
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);
        assertThatCode(() -> attempts.checkEmail(EMAIL)).doesNotThrowAnyException();

        attempts.recordFailure(EMAIL);

        assertThatThrownBy(() -> attempts.checkEmail(EMAIL))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        assertThatCode(() -> attempts.checkEmail("autre@exemple.fr")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterCountsDownFromTheFirstFailure() {
        attempts.recordFailure(EMAIL);
        clock.advance(Duration.ofMinutes(10));
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);

        assertThatThrownBy(() -> attempts.checkEmail(EMAIL))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(5)));
    }

    @Test
    void emailIsUnblockedWhenTheWindowEnds() {
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);

        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> attempts.checkEmail(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void successClearsFailures() {
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);

        attempts.recordSuccess(EMAIL);
        attempts.recordFailure(EMAIL);
        attempts.recordFailure(EMAIL);

        assertThatCode(() -> attempts.checkEmail(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void ipIsBlockedBeyondItsQuotaUntilTheWindowEnds() {
        attempts.acquireForIp(IP);
        attempts.acquireForIp(IP);

        assertThatThrownBy(() -> attempts.acquireForIp(IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(1)));
        assertThatCode(() -> attempts.acquireForIp("198.51.100.1")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> attempts.acquireForIp(IP)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        attempts.acquireForIp(IP);
        attempts.acquireForIp(IP);
        clock.advance(Duration.ofMillis(59_500));

        assertThatThrownBy(() -> attempts.acquireForIp(IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(1));
    }

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
