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
    private final PatternMapper mapper;

    public List<PatternSummary> list(long ownerId) {
        return repository.findAllSummaries(ownerId).stream().map(mapper::toSummary).toList();
    }

    public PatternResponse get(long ownerId, long id) {
        return mapper.toResponse(find(ownerId, id));
    }

    @Transactional
    public PatternResponse create(long ownerId, PatternRequest request) {
        Pattern pattern = mapper.toEntity(request);
        pattern.setOwnerId(ownerId);
        return mapper.toResponse(repository.save(pattern));
    }

    @Transactional
    public PatternResponse replace(long ownerId, long id, PatternRequest request) {
        // locked: the progress is clamped against the new steps and must not move meanwhile
        Pattern pattern = findForUpdate(ownerId, id);
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        if (pattern.getProgress() != null) {
            pattern.getProgress().clampTo(pattern.getSteps());
        }
        pattern.touch();
        repository.flush();
        return mapper.toResponse(pattern);
    }

    @Transactional
    public void delete(long ownerId, long id) {
        // locked like every other write to the pattern, so concurrent writes wait instead of deadlocking
        repository.delete(findForUpdate(ownerId, id));
    }

    /**
     * Another account's pattern is reported exactly like a missing one. Public: it is called through
     * the Spring proxy by ProgressService, and a non-public method on a CGLIB proxy is not a safe call.
     */
    public Pattern find(long ownerId, long id) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new PatternNotFoundException(id));
    }

    /** {@link #find} for a write to the pattern's progress: the row stays locked until commit. */
    public Pattern findForUpdate(long ownerId, long id) {
        return repository.findByIdAndOwnerIdForUpdate(id, ownerId)
                .orElseThrow(() -> new PatternNotFoundException(id));
    }
}
