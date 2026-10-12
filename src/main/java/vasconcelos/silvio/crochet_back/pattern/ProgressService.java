package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ProgressService {

    private final PatternService patterns;
    private final PatternRepository repository;
    private final PatternMapper mapper;

    public ProgressResponse update(long ownerId, long patternId, ProgressRequest request) {
        Pattern pattern = patterns.find(ownerId, patternId);
        List<Step> steps = pattern.getSteps();
        int stepIndex = request.stepIndex();
        int rowIndex = request.rowIndex();
        if (stepIndex >= steps.size()) {
            throw new InvalidFieldException("stepIndex", "must be less than " + steps.size());
        }
        int rowCount = steps.get(stepIndex).getRows().size();
        if (rowIndex >= rowCount) {
            throw new InvalidFieldException("rowIndex", "must be less than " + rowCount);
        }
        PatternProgress progress = pattern.updateProgress(request.status(), stepIndex, rowIndex);
        repository.flush();
        return mapper.toProgressResponse(progress);
    }

    public void abandon(long ownerId, long patternId) {
        Pattern pattern = patterns.find(ownerId, patternId);
        if (pattern.getProgress() == null) {
            throw new ProgressNotFoundException(patternId);
        }
        pattern.clearProgress();
    }
}
