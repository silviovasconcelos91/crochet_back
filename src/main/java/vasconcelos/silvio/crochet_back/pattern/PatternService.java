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
        return repository.findAllSummaries(accountId).stream().map(row -> mapper.toSummary(row, accountId)).toList();
    }

    public List<PatternSummary> listPublic(long accountId) {
        return repository.findPublicSummaries(accountId).stream().map(row -> mapper.toSummary(row, accountId)).toList();
    }

    public PatternResponse get(long accountId, long id) {
        Pattern pattern = repository.findReadable(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
        return respond(pattern, accountId);
    }

    private PatternResponse respond(Pattern pattern, long accountId) {
        PatternProgress mine = progress.findById(new PatternProgress.Key(pattern.getId(), accountId)).orElse(null);
        return mapper.toResponse(pattern, mine, repository.findAuthorUsername(pattern.getOwnerId()), accountId);
    }

    @Transactional
    public PatternResponse create(long accountId, PatternRequest request) {
        Pattern pattern = mapper.toEntity(request);
        pattern.setOwnerId(accountId);
        return respond(repository.save(pattern), accountId);
    }

    @Transactional
    public PatternResponse replace(long accountId, long id, PatternRequest request) {
        // locked: every account's progress is clamped against the new steps and must not move meanwhile
        Pattern pattern = findForWrite(accountId, id);
        boolean wasPublic = pattern.getVisibility() == Visibility.PUBLIC;
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        pattern.touch();
        repository.flush();
        if (wasPublic && pattern.getVisibility() == Visibility.PRIVATE) {
            // private means the owner's alone: the followers lose their access and their progress
            progress.deleteOthers(id, accountId);
        }
        progress.findAllByPatternIdForUpdate(id).forEach(row -> row.clampTo(pattern.getSteps()));
        progress.flush();
        return respond(pattern, accountId);
    }

    @Transactional
    public void delete(long accountId, long id) {
        // locked like every other write to the pattern; progress rows go through ON DELETE CASCADE
        repository.delete(findForWrite(accountId, id));
    }

    /** The pattern, exclusively locked, for its owner: 404 when invisible, 403 when public but not theirs. */
    Pattern findForWrite(long accountId, long id) {
        Pattern pattern = repository.findReadableForUpdate(id, accountId)
                .orElseThrow(() -> new PatternNotFoundException(id));
        if (pattern.getOwnerId() != accountId) {
            throw new PatternAccessDeniedException(id);
        }
        return pattern;
    }

    /**
     * The pattern, share-locked, for a progress write. Public: called through the Spring proxy by
     * ProgressService, and a non-public method on a CGLIB proxy is not a safe call.
     */
    public Pattern findForProgress(long accountId, long id) {
        return repository.findReadableForShare(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
    }
}
