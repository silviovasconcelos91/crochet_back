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
            new LoginLimitProperties(3, 5, Duration.ofMinutes(15), 2, Duration.ofMinutes(1)), clock);

    @Test
    void emailIsRefusedForAClientOnceItsAttemptsReachTheLimit() {
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        assertThatCode(() -> attempts.acquireForEmail("autre@exemple.fr", IP)).doesNotThrowAnyException();
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
                        attempts.acquireForEmail(EMAIL, IP);
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
    void otherClientsKeepTheirOwnQuotaForTheSameEmail() {
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, IP)).isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> attempts.acquireForEmail(EMAIL, "198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void emailIsRefusedForEveryClientOnceItsOverallLimitIsReached() {
        for (int i = 1; i <= 5; i++) {
            attempts.acquireForEmail(EMAIL, "198.51.100." + i);
        }

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, "198.51.100.99"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void attemptsRefusedForOneClientDoNotUseTheOverallQuota() {
        for (int i = 0; i < 6; i++) {
            try {
                attempts.acquireForEmail(EMAIL, IP);
            } catch (TooManyAttemptsException refused) {
                // three granted, three refused
            }
        }

        assertThatCode(() -> attempts.acquireForEmail(EMAIL, "198.51.100.1")).doesNotThrowAnyException();
        assertThatCode(() -> attempts.acquireForEmail(EMAIL, "198.51.100.2")).doesNotThrowAnyException();
        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, "198.51.100.3"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void successGivesTheOverallQuotaBack() {
        for (int i = 1; i <= 4; i++) {
            attempts.acquireForEmail(EMAIL, "198.51.100." + i);
        }

        attempts.recordSuccess(EMAIL, IP);

        for (int i = 5; i <= 9; i++) {
            attempts.acquireForEmail(EMAIL, "198.51.100." + i);
        }
    }

    @Test
    void wrongPasswordKeepsTheAttemptCounted() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> attempts.checkLogin(EMAIL, IP, WrongPassword.class, () -> {
                throw new WrongPassword();
            })).isInstanceOf(WrongPassword.class);
        }

        assertThatThrownBy(() -> attempts.checkLogin(EMAIL, IP, WrongPassword.class, () -> "ok"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void serverFaultGivesTheAttemptBack() {
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> attempts.checkLogin(EMAIL, IP, WrongPassword.class, () -> {
                throw new IllegalStateException("database is down");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(attempts.checkLogin(EMAIL, IP, WrongPassword.class, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void successfulCheckGivesTheWholeQuotaBack() {
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        attempts.checkLogin(EMAIL, IP, WrongPassword.class, () -> "ok");

        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);
        assertThatCode(() -> attempts.acquireForEmail(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void checksFromASessionIgnoreTheOverallCeiling() {
        for (int i = 1; i <= 5; i++) {
            attempts.acquireForEmail(EMAIL, "198.51.100." + i);
        }
        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, IP)).isInstanceOf(TooManyAttemptsException.class);

        assertThatCode(() -> attempts.checkFromSession(EMAIL, IP, WrongPassword.class, () -> { }))
                .doesNotThrowAnyException();
    }

    @Test
    void checksFromASessionStillHaveTheClientQuota() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> attempts.checkFromSession(EMAIL, IP, WrongPassword.class, () -> {
                throw new WrongPassword();
            })).isInstanceOf(WrongPassword.class);
        }

        assertThatThrownBy(() -> attempts.checkFromSession(EMAIL, IP, WrongPassword.class, () -> { }))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    static final class WrongPassword extends RuntimeException {
    }

    @Test
    void retryAfterCountsDownFromTheFirstAttempt() {
        attempts.acquireForEmail(EMAIL, IP);
        clock.advance(Duration.ofMinutes(10));
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        assertThatThrownBy(() -> attempts.acquireForEmail(EMAIL, IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(5)));
    }

    @Test
    void emailIsAcceptedAgainWhenTheWindowEnds() {
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> attempts.acquireForEmail(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void successClearsAttempts() {
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        attempts.recordSuccess(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);
        attempts.acquireForEmail(EMAIL, IP);

        assertThatCode(() -> attempts.acquireForEmail(EMAIL, IP)).doesNotThrowAnyException();
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
