package vasconcelos.silvio.crochet_back.pattern;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

@RestController
@RequestMapping("/api/patterns/{id}/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService service;

    @PutMapping
    public ProgressResponse update(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody ProgressRequest request) {
        return service.update(principal.id(), id, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandon(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long id) {
        service.abandon(principal.id(), id);
    }
}
