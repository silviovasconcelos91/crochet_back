package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternService {

    private final PatternRepository repository;
    private final PatternProgressRepository progress;
    private final PatternMapper mapper;

    public List<PatternSummary> list(long accountId) {
        return repository.findAllSummaries(accountId).stream().map(mapper::toSummary).toList();
    }

    public PatternResponse get(long accountId, long id) {
        Pattern pattern = repository.findReadable(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
        return mapper.toResponse(pattern, progress.findById(new PatternProgress.Key(id, accountId)).orElse(null));
    }

    @Transactional
    public PatternResponse create(long accountId, PatternRequest request) {
        Pattern pattern = mapper.toEntity(request);
        pattern.setOwnerId(accountId);
        return mapper.toResponse(repository.save(pattern), null);
    }

    @Transactional
    public PatternResponse replace(long accountId, long id, PatternRequest request) {
        // locked: every account's progress is clamped against the new steps and must not move meanwhile
        Pattern pattern = findForWrite(accountId, id);
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        pattern.touch();
        repository.flush();
        progress.findAllByPatternIdForUpdate(id).forEach(row -> row.clampTo(pattern.getSteps()));
        progress.flush();
        return mapper.toResponse(pattern, progress.findById(new PatternProgress.Key(id, accountId)).orElse(null));
    }

    @Transactional
    public void delete(long accountId, long id) {
        // locked like every other write to the pattern; progress rows go through ON DELETE CASCADE
        repository.delete(findForWrite(accountId, id));
    }

    /** The pattern, exclusively locked, for its owner. */
    Pattern findForWrite(long accountId, long id) {
        return repository.findReadableForUpdate(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
    }

    /**
     * The pattern, share-locked, for a progress write. Public: called through the Spring proxy by
     * ProgressService, and a non-public method on a CGLIB proxy is not a safe call.
     */
    public Pattern findForProgress(long accountId, long id) {
        return repository.findReadableForShare(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
    }
}
