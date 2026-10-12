package vasconcelos.silvio.crochet_back.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles authentication: failed logins per email (against password guessing on one account) and
 * requests per client address (against trying many emails). Counters live in this JVM only: they
 * reset on restart and are not shared between instances.
 */
@Component
public class LoginAttempts {

    private final FixedWindowCounter failuresByEmail;
    private final FixedWindowCounter requestsByIp;

    @Autowired
    public LoginAttempts(LoginLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttempts(LoginLimitProperties properties, Clock clock) {
        failuresByEmail = new FixedWindowCounter(properties.maxFailuresPerEmail(), properties.emailWindow(), clock);
        requestsByIp = new FixedWindowCounter(properties.maxRequestsPerIp(), properties.ipWindow(), clock);
    }

    /** Counts one request from {@code ip}, refused once the address is over its quota. */
    public void acquireForIp(String ip) {
        requestsByIp.add(ip);
        requestsByIp.check(ip, requestsByIp.limit + 1);
    }

    /** Refuses a login attempt for an email that has reached its failure limit. */
    public void checkEmail(String email) {
        failuresByEmail.check(email, failuresByEmail.limit);
    }

    public void recordFailure(String email) {
        failuresByEmail.add(email);
    }

    public void recordSuccess(String email) {
        failuresByEmail.reset(email);
    }

    private static final class FixedWindowCounter {

        /** Above this many keys, expired windows are purged so that the map cannot grow without bound. */
        private static final int SWEEP_THRESHOLD = 10_000;

        private record Window(Instant start, int count) {
        }

        private final int limit;
        private final Duration length;
        private final Clock clock;
        private final Map<String, Window> windows = new ConcurrentHashMap<>();

        FixedWindowCounter(int limit, Duration length, Clock clock) {
            this.limit = limit;
            this.length = length;
            this.clock = clock;
        }

        void add(String key) {
            Instant now = clock.instant();
            windows.compute(key, (k, current) -> current == null || isOver(current, now)
                    ? new Window(now, 1)
                    : new Window(current.start(), current.count() + 1));
            if (windows.size() > SWEEP_THRESHOLD) {
                windows.values().removeIf(window -> isOver(window, now));
            }
        }

        void check(String key, int blockedFrom) {
            Instant now = clock.instant();
            Window window = windows.get(key);
            if (window != null && !isOver(window, now) && window.count() >= blockedFrom) {
                throw new TooManyAttemptsException(Duration.between(now, window.start().plus(length)));
            }
        }

        void reset(String key) {
            windows.remove(key);
        }

        private boolean isOver(Window window, Instant now) {
            return !now.isBefore(window.start().plus(length));
        }
    }
}
