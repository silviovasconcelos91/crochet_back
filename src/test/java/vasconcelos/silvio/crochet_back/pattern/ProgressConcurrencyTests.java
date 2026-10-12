package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProgressConcurrencyTests {

    static final ProgressRequest START = new ProgressRequest(ProgressStatus.IN_PROGRESS, 0, 0);

    @Autowired
    PatternService patterns;

    @Autowired
    ProgressService progress;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;
    long patternId;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.create(accounts, passwordEncoder, "alice").id();
        bob = TestAccounts.create(accounts, passwordEncoder, "bob").id();
        patternId = patterns.create(alice, new PatternRequest("Chat", null, null, null, null, null,
                List.of(new StepRequest("A", List.of(new RowRequest("1", "6ms")))), Visibility.PUBLIC)).id();
    }

    /** Runs {@code first} in a transaction left open until the returned latch is released. */
    CountDownLatch holdOpen(Runnable first, CompletableFuture<Void> done) {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            first.run();
            started.countDown();
            await(release);
        })).whenComplete((ok, error) -> {
            if (error != null) {
                done.completeExceptionally(error);
            } else {
                done.complete(null);
            }
        });
        await(started);
        return release;
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void twoAccountsWriteProgressWithoutWaitingForEachOther() throws Exception {
        CompletableFuture<Void> aliceDone = new CompletableFuture<>();
        CountDownLatch release = holdOpen(() -> progress.update(alice, patternId, START), aliceDone);
        try {
            // Alice's transaction still holds its share lock on the pattern row
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> progress.update(bob, patternId, START));
        } finally {
            release.countDown();
        }
        aliceDone.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentFirstWritesOfOneAccountDoNotFail() throws Exception {
        CompletableFuture<Void> firstDone = new CompletableFuture<>();
        CountDownLatch release = holdOpen(() -> progress.update(bob, patternId, START), firstDone);
        try {
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> progress.update(bob, patternId,
                    new ProgressRequest(ProgressStatus.FINISHED, 0, 0)));
            // the second insert waits on the first one's uncommitted row; bounded so CI cannot hang
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("select count(*) from pg_stat_activity"
                    + " where wait_event_type = 'Lock' and datname = current_database()", Integer.class) == 0) {
                assertThat(System.nanoTime()).as("second write never blocked on the first").isLessThan(deadline);
                Thread.onSpinWait();
            }
            release.countDown();

            firstDone.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("select status from pattern_progress", String.class)).isEqualTo("FINISHED");
    }
}
