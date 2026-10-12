package vasconcelos.silvio.crochet_back.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Throttles password checks: per email and client address (against guessing one account's password),
 * per email for all clients together (a higher ceiling against guessing from many addresses), and
 * requests per client address (against trying many emails). Since one client only exhausts its own
 * quota for an email, an attacker cannot lock the owner out without many addresses. Counters live in
 * this JVM only: they reset on restart and are not shared between instances.
 */
@Component
public class LoginAttempts {

    private final FixedWindowCounter attemptsByEmailAndClient;
    private final FixedWindowCounter attemptsByEmail;
    private final FixedWindowCounter requestsByIp;

    @Autowired
    public LoginAttempts(LoginLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttempts(LoginLimitProperties properties, Clock clock) {
        attemptsByEmailAndClient = new FixedWindowCounter(
                properties.maxFailuresPerEmailAndClient(), properties.emailWindow(), clock);
        attemptsByEmail = new FixedWindowCounter(properties.maxFailuresPerEmail(), properties.emailWindow(), clock);
        requestsByIp = new FixedWindowCounter(properties.maxRequestsPerIp(), properties.ipWindow(), clock);
    }

    /** Counts one request from {@code ip}, refused once the address is over its quota. */
    public void acquireForIp(String ip) {
        requestsByIp.acquire(clientKey(ip));
    }

    /**
     * Counts one password check for {@code email} from {@code ip} before the password is verified,
     * refused once either quota is used. Reserving up front (rather than recording failures
     * afterwards) keeps parallel attempts from all slipping through while the first ones are still
     * being hashed. An attempt the client quota refuses gives its overall reservation back.
     */
    public void acquireForEmail(String email, String ip) {
        attemptsByEmail.acquire(email);
        try {
            attemptsByEmailAndClient.acquire(pairKey(email, ip));
        } catch (TooManyAttemptsException refused) {
            attemptsByEmail.release(email);
            throw refused;
        }
    }

    /** A successful check gives the email its whole quota back. */
    public void recordSuccess(String email, String ip) {
        attemptsByEmailAndClient.reset(pairKey(email, ip));
        attemptsByEmail.reset(email);
    }

    private static String pairKey(String email, String ip) {
        return clientKey(ip) + " " + email;
    }

    /**
     * IPv6 clients usually hold a whole /64, so they are counted by that prefix; anything else is
     * counted per address.
     */
    static String clientKey(String address) {
        if (address.indexOf(':') < 0) {
            return address;
        }
        try {
            // a literal address: parsed, never resolved through DNS
            InetAddress parsed = InetAddress.getByName(address);
            if (parsed instanceof Inet6Address) {
                return HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "::/64";
            }
            return parsed.getHostAddress();
        } catch (UnknownHostException notAnAddress) {
            return address;
        }
    }

    private static final class FixedWindowCounter {

        /** Above this many keys, expired windows are purged, at most once per window length. */
        private static final int SWEEP_THRESHOLD = 10_000;

        private record Window(Instant start, int count) {
        }

        private final int limit;
        private final Duration length;
        private final Clock clock;
        private final Map<String, Window> windows = new ConcurrentHashMap<>();
        private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.MIN);

        FixedWindowCounter(int limit, Duration length, Clock clock) {
            this.limit = limit;
            this.length = length;
            this.clock = clock;
        }

        /** Counts one hit for {@code key}, atomically refusing it (uncounted) once the limit is reached. */
        void acquire(String key) {
            Instant now = clock.instant();
            Window[] refused = new Window[1];
            windows.compute(key, (k, current) -> {
                if (current == null || isOver(current, now)) {
                    return new Window(now, 1);
                }
                if (current.count() >= limit) {
                    refused[0] = current;
                    return current;
                }
                return new Window(current.start(), current.count() + 1);
            });
            sweepIfLarge(now);
            if (refused[0] != null) {
                throw new TooManyAttemptsException(Duration.between(now, refused[0].start().plus(length)));
            }
        }

        /** Gives back one hit counted by {@link #acquire} in the current window. */
        void release(String key) {
            windows.computeIfPresent(key, (k, current) ->
                    current.count() <= 1 ? null : new Window(current.start(), current.count() - 1));
        }

        void reset(String key) {
            windows.remove(key);
        }

        private void sweepIfLarge(Instant now) {
            Instant previous = lastSweep.get();
            if (windows.size() > SWEEP_THRESHOLD
                    && !now.isBefore(previous.plus(length))
                    && lastSweep.compareAndSet(previous, now)) {
                windows.values().removeIf(window -> isOver(window, now));
            }
        }

        private boolean isOver(Window window, Instant now) {
            return !now.isBefore(window.start().plus(length));
        }
    }
}
