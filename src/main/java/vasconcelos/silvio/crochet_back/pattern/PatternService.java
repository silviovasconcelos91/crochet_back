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

    public List<PatternSummary> list() {
        return repository.findAllSummaries();
    }

    public PatternResponse get(long id) {
        return mapper.toResponse(find(id));
    }

    @Transactional
    public PatternResponse create(PatternRequest request) {
        return mapper.toResponse(repository.save(mapper.toEntity(request)));
    }

    @Transactional
    public PatternResponse replace(long id, PatternRequest request) {
        Pattern pattern = find(id);
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        pattern.touch();
        repository.flush();
        return mapper.toResponse(pattern);
    }

    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
    }

    private Pattern find(long id) {
        return repository.findById(id).orElseThrow(() -> new PatternNotFoundException(id));
    }
}
