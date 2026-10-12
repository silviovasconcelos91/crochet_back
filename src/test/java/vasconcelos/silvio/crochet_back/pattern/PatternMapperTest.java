package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.Test;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMapperTest {

    private final PatternMapper mapper = new PatternMapper();

    private final PatternRequest request = new PatternRequest(
            "Chat", "Petit chat", new BigDecimal("3.5"), "Coton", "https://src", "https://img",
            List.of(
                    new StepRequest("Tête", List.of(
                            new RowRequest("1", "6ms (6)"),
                            new RowRequest("2", "6aug (12)"))),
                    new StepRequest("Corps", List.of(
                            new RowRequest("1-5", "12ms (12)")))));

    @Test
    void toEntityCopiesFieldsAndAssignsPositionsFromListOrder() {
        Pattern pattern = mapper.toEntity(request);

        assertThat(pattern.getTitle()).isEqualTo("Chat");
        assertThat(pattern.getDescription()).isEqualTo("Petit chat");
        assertThat(pattern.getHookSizeMm()).isEqualByComparingTo("3.5");
        assertThat(pattern.getYarn()).isEqualTo("Coton");
        assertThat(pattern.getSourceUrl()).isEqualTo("https://src");
        assertThat(pattern.getImageUrl()).isEqualTo("https://img");

        assertThat(pattern.getSteps()).extracting(Step::getPosition).containsExactly(0, 1);
        assertThat(pattern.getSteps()).extracting(Step::getTitle).containsExactly("Tête", "Corps");
        assertThat(pattern.getSteps()).allSatisfy(step -> assertThat(step.getPattern()).isSameAs(pattern));

        Step head = pattern.getSteps().getFirst();
        assertThat(head.getRows()).extracting(PatternRow::getPosition).containsExactly(0, 1);
        assertThat(head.getRows()).extracting(PatternRow::getNumber).containsExactly("1", "2");
        assertThat(head.getRows()).extracting(PatternRow::getValue).containsExactly("6ms (6)", "6aug (12)");
        assertThat(head.getRows()).allSatisfy(row -> assertThat(row.getStep()).isSameAs(head));
    }

    @Test
    void toResponseKeepsStepAndRowOrder() {
        PatternResponse response = mapper.toResponse(mapper.toEntity(request));

        assertThat(response.title()).isEqualTo("Chat");
        assertThat(response.hookSizeMm()).isEqualByComparingTo("3.5");
        assertThat(response.steps()).extracting(StepResponse::title).containsExactly("Tête", "Corps");
        assertThat(response.steps().getFirst().rows()).containsExactly(
                new RowResponse("1", "6ms (6)"),
                new RowResponse("2", "6aug (12)"));
    }

    @Test
    void applyFieldsOverwritesTopLevelFieldsOnly() {
        Pattern pattern = mapper.toEntity(request);
        PatternRequest update = new PatternRequest(
                "Chat v2", null, null, null, null, null, List.of());

        mapper.applyFields(update, pattern);

        assertThat(pattern.getTitle()).isEqualTo("Chat v2");
        assertThat(pattern.getDescription()).isNull();
        assertThat(pattern.getHookSizeMm()).isNull();
        assertThat(pattern.getYarn()).isNull();
        assertThat(pattern.getSourceUrl()).isNull();
        assertThat(pattern.getImageUrl()).isNull();
        assertThat(pattern.getSteps()).hasSize(2);
    }

    @Test
    void toResponseHasNoProgressUntilStartedThenMapsIt() {
        Pattern pattern = mapper.toEntity(request);
        assertThat(mapper.toResponse(pattern).progress()).isNull();

        pattern.updateProgress(ProgressStatus.IN_PROGRESS, 1, 0);

        ProgressResponse progress = mapper.toResponse(pattern).progress();
        assertThat(progress.status()).isEqualTo(ProgressStatus.IN_PROGRESS);
        assertThat(progress.stepIndex()).isEqualTo(1);
        assertThat(progress.rowIndex()).isZero();
        assertThat(progress.startedAt()).isNotNull();
        assertThat(progress.finishedAt()).isNull();
    }
}
