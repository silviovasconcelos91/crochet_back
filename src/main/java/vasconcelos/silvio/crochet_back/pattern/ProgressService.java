package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.Timestamps;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ProgressService {

    private final PatternService patterns;
    private final PatternProgressRepository progress;
    private final PatternMapper mapper;

    public ProgressResponse update(long accountId, long patternId, ProgressRequest request) {
        Pattern pattern = patterns.findForProgress(accountId, patternId);
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
        progress.insertIfMissing(patternId, accountId, Timestamps.now());
        PatternProgress row = progress.findForUpdate(patternId, accountId).orElseThrow();
        row.update(request.status(), stepIndex, rowIndex);
        progress.flush();
        return mapper.toProgressResponse(row);
    }

    public void abandon(long accountId, long patternId) {
        patterns.findForProgress(accountId, patternId);
        if (progress.deleteByKey(patternId, accountId) == 0) {
            throw new ProgressNotFoundException(patternId);
        }
    }
}
