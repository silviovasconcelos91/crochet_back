package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.Account;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PatternRepositoryTests {

    @Autowired
    PatternRepository repository;

    @Autowired
    AccountRepository accounts;

    @Autowired
    EntityManager em;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    JdbcTemplate jdbc;

    Long ownerId;

    @BeforeEach
    void createOwner() {
        // other classes sharing this context commit rows; the truncate is rolled back with the test
        TestDatabase.reset(jdbc);
        ownerId = accounts.save(new Account("moi", "moi@exemple.fr", "hash")).getId();
    }

    @Test
    void persistsStepsAndRowsOrderedByPosition() {
        Pattern pattern = new Pattern();
        pattern.setTitle("Chat");
        pattern.setOwnerId(ownerId);
        Step head = new Step(0, "Tête");
        head.addRow(new PatternRow(1, "2", "6aug (12)"));
        head.addRow(new PatternRow(0, "1", "6ms (6)"));
        Step body = new Step(1, "Corps");
        body.addRow(new PatternRow(0, "1-5", "12ms (12)"));
        // inserted out of order on purpose: reading must sort by position
        pattern.addStep(body);
        pattern.addStep(head);

        Long id = repository.save(pattern).getId();
        em.flush();
        em.clear();

        Pattern loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getSteps()).extracting(Step::getTitle).containsExactly("Tête", "Corps");
        assertThat(loaded.getSteps().getFirst().getRows())
                .extracting(PatternRow::getValue)
                .containsExactly("6ms (6)", "6aug (12)");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
    }

    @Test
    void findAllSummariesSortsByTitleIgnoringCaseAndCountsSteps() {
        repository.save(pattern("zèbre", 1));
        repository.save(pattern("Chat", 2));
        repository.save(pattern("abeille", 3));

        List<PatternSummaryRow> summaries = repository.findAllSummaries(ownerId);

        assertThat(summaries).extracting(PatternSummaryRow::title).containsExactly("abeille", "Chat", "zèbre");
        assertThat(summaries).extracting(PatternSummaryRow::stepCount).containsExactly(3, 2, 1);
    }

    @Test
    void findAllSummariesBreaksTitleTiesById() {
        Pattern first = repository.save(pattern("Bonnet", 1));
        Pattern second = repository.save(pattern("bonnet", 1));
        // updating the first row rewrites its tuple after the second one in the heap,
        // so an order relying on physical position would return second before first
        first.setDescription("modifié");
        em.flush();

        List<PatternSummaryRow> summaries = repository.findAllSummaries(ownerId);

        assertThat(summaries).extracting(PatternSummaryRow::id).containsExactly(first.getId(), second.getId());
    }

    @Test
    void findAllSummariesAndFindByIdAreScopedToTheOwner() {
        Long otherOwner = accounts.save(new Account("autre", "autre@exemple.fr", "hash")).getId();
        Pattern mine = repository.save(pattern("Mien", 1));
        Pattern theirs = pattern("Sien", 1);
        theirs.setOwnerId(otherOwner);
        repository.save(theirs);

        assertThat(repository.findAllSummaries(ownerId)).extracting(PatternSummaryRow::title).containsExactly("Mien");
        assertThat(repository.findByIdAndOwnerId(mine.getId(), ownerId)).isPresent();
        assertThat(repository.findByIdAndOwnerId(theirs.getId(), ownerId)).isEmpty();
    }

    @Test
    void findByIdAndOwnerIdLoadsTheProgressInTheSameQuery() {
        Long id = repository.save(pattern("Chat", 1)).getId();
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Pattern loaded = repository.findByIdAndOwnerId(id, ownerId).orElseThrow();

        assertThat(loaded.getProgress()).isNull();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private Pattern pattern(String title, int stepCount) {
        Pattern pattern = new Pattern();
        pattern.setTitle(title);
        pattern.setOwnerId(ownerId);
        for (int i = 0; i < stepCount; i++) {
            Step step = new Step(i, "Étape " + i);
            step.addRow(new PatternRow(0, "1", "6ms"));
            pattern.addStep(step);
        }
        return pattern;
    }
}
