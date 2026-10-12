package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.stereotype.Component;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepResponse;

import java.util.ArrayList;
import java.util.List;

@Component
public class PatternMapper {

    /** {@code null} when the pattern is not started. */
    public ProgressResponse toProgressResponse(PatternProgress progress) {
        if (progress == null) {
            return null;
        }
        return new ProgressResponse(
                progress.getStatus(),
                progress.getStepPosition(),
                progress.getRowPosition(),
                progress.getStartedAt(),
                progress.getUpdatedAt(),
                progress.getFinishedAt());
    }

    public Pattern toEntity(PatternRequest request) {
        Pattern pattern = new Pattern();
        applyFields(request, pattern);
        toSteps(request.steps()).forEach(pattern::addStep);
        return pattern;
    }

    public void applyFields(PatternRequest request, Pattern pattern) {
        pattern.setTitle(request.title());
        pattern.setDescription(request.description());
        pattern.setHookSizeMm(request.hookSizeMm());
        pattern.setYarn(request.yarn());
        pattern.setSourceUrl(request.sourceUrl());
        pattern.setImageUrl(request.imageUrl());
        // omitted means private, on PUT too: forgetting the field never publishes a pattern
        pattern.setVisibility(request.visibility() == null ? Visibility.PRIVATE : request.visibility());
    }

    /** Builds detached steps whose positions follow the list order (0-based). */
    public List<Step> toSteps(List<StepRequest> requests) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            StepRequest stepRequest = requests.get(i);
            Step step = new Step(i, stepRequest.title());
            List<RowRequest> rows = stepRequest.rows();
            for (int j = 0; j < rows.size(); j++) {
                step.addRow(new PatternRow(j, rows.get(j).number(), rows.get(j).value()));
            }
            steps.add(step);
        }
        return steps;
    }

    /** {@code progress} is the logged-in account's, {@code null} when it has not started. */
    public PatternResponse toResponse(Pattern pattern, PatternProgress progress) {
        List<StepResponse> steps = pattern.getSteps().stream()
                .map(step -> new StepResponse(step.getTitle(), step.getRows().stream()
                        .map(row -> new RowResponse(row.getNumber(), row.getValue()))
                        .toList()))
                .toList();
        return new PatternResponse(
                pattern.getId(),
                pattern.getTitle(),
                pattern.getDescription(),
                pattern.getHookSizeMm(),
                pattern.getYarn(),
                pattern.getSourceUrl(),
                pattern.getImageUrl(),
                pattern.getCreatedAt(),
                pattern.getUpdatedAt(),
                steps,
                toProgressResponse(progress),
                pattern.getVisibility());
    }

    public PatternSummary toSummary(PatternSummaryRow row) {
        ProgressResponse progress = row.status() == null ? null : new ProgressResponse(
                row.status(), row.stepPosition(), row.rowPosition(),
                row.startedAt(), row.updatedAt(), row.finishedAt());
        return new PatternSummary(
                row.id(), row.title(), row.description(), row.hookSizeMm(), row.yarn(), row.imageUrl(),
                row.stepCount(), progress, row.visibility());
    }
}
