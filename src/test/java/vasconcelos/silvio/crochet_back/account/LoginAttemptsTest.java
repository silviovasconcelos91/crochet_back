package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
    void emailIsRefusedOnceItsAttemptsReachTheLimit() {
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        assertThatCode(() -> attempts.acquireForEmail("autre@exemple.fr")).doesNotThrowAnyException();
    }

    @Test
    void concurrentAttemptsCannotExceedTheLimit() throws Exception {
        int threads = 32;
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    try {
                        attempts.acquireForEmail(EMAIL);
                        granted.incrementAndGet();
                    } catch (TooManyAttemptsException refused) {
                        // expected for all but three
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(granted).hasValue(3);
    }

    @Test
    void retryAfterCountsDownFromTheFirstAttempt() {
        attempts.acquireForEmail(EMAIL);
        clock.advance(Duration.ofMinutes(10));
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(5)));
    }

    @Test
    void emailIsAcceptedAgainWhenTheWindowEnds() {
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);

        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> attempts.acquireForEmail(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void successClearsAttempts() {
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);

        attempts.recordSuccess(EMAIL);
        attempts.acquireForEmail(EMAIL);
        attempts.acquireForEmail(EMAIL);

        assertThatCode(() -> attempts.acquireForEmail(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void ipIsRefusedBeyondItsQuotaUntilTheWindowEnds() {
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
    void ipv6AddressesShareTheQuotaOfTheirSlash64() {
        attempts.acquireForIp("2001:db8:1:2::1");
        attempts.acquireForIp("2001:db8:1:2:ffff:ffff:ffff:ffff");

        assertThatThrownBy(() -> attempts.acquireForIp("2001:0db8:0001:0002:0:0:0:abcd"))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> attempts.acquireForIp("2001:db8:1:3::1")).doesNotThrowAnyException();
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

        private volatile Instant now = Instant.parse("2026-10-12T10:00:00Z");

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
