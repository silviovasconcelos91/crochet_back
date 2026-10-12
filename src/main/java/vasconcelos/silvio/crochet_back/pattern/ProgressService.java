package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.Timestamps;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

import java.util.List;
import java.util.Optional;

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
        PatternProgress row = lockOrStart(patternId, accountId);
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

    /**
     * Starts the progress if missing, then locks it. The same account's abandon can delete the row
     * between the two statements (both only hold a shared lock on the pattern), so retry a few times.
     */
    private PatternProgress lockOrStart(long patternId, long accountId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            progress.insertIfMissing(patternId, accountId, Timestamps.now());
            Optional<PatternProgress> row = progress.findForUpdate(patternId, accountId);
            if (row.isPresent()) {
                return row.get();
            }
        }
        throw new IllegalStateException("Progress of pattern " + patternId + " for account " + accountId
                + " keeps disappearing while it is being updated");
    }
}
