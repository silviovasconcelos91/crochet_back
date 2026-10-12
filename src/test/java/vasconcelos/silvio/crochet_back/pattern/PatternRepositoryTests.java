package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PatternRepositoryTests {

    @Autowired
    PatternRepository repository;

    @Autowired
    EntityManager em;

    @Test
    void persistsStepsAndRowsOrderedByPosition() {
        Pattern pattern = new Pattern();
        pattern.setTitle("Chat");
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

        List<PatternSummary> summaries = repository.findAllSummaries();

        assertThat(summaries).extracting(PatternSummary::title).containsExactly("abeille", "Chat", "zèbre");
        assertThat(summaries).extracting(PatternSummary::stepCount).containsExactly(3, 2, 1);
    }

    @Test
    void findAllSummariesBreaksTitleTiesById() {
        Pattern first = repository.save(pattern("Bonnet", 1));
        Pattern second = repository.save(pattern("bonnet", 1));
        // updating the first row rewrites its tuple after the second one in the heap,
        // so an order relying on physical position would return second before first
        first.setDescription("modifié");
        em.flush();

        List<PatternSummary> summaries = repository.findAllSummaries();

        assertThat(summaries).extracting(PatternSummary::id).containsExactly(first.getId(), second.getId());
    }

    private static Pattern pattern(String title, int stepCount) {
        Pattern pattern = new Pattern();
        pattern.setTitle(title);
        for (int i = 0; i < stepCount; i++) {
            Step step = new Step(i, "Étape " + i);
            step.addRow(new PatternRow(0, "1", "6ms"));
            pattern.addStep(step);
        }
        return pattern;
    }
}
