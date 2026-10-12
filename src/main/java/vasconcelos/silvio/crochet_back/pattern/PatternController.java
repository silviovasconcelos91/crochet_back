package vasconcelos.silvio.crochet_back.pattern;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
public class PatternController {

    private final PatternService service;

    @GetMapping
    public List<PatternSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PatternResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<PatternResponse> create(@Valid @RequestBody PatternRequest request) {
        PatternResponse created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public PatternResponse replace(@PathVariable long id, @Valid @RequestBody PatternRequest request) {
        return service.replace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
