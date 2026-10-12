package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;
import vasconcelos.silvio.crochet_back.account.dto.ChangePasswordRequest;
import vasconcelos.silvio.crochet_back.account.dto.DeleteAccountRequest;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;
    private final AccountSessions accountSessions;

    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AccountResponse.from(accountService.get(principal.id()));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        accountService.changePassword(
                principal.id(), body.currentPassword(), body.newPassword(), request.getSession().getId());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest body,
            HttpServletRequest request) {
        accountService.delete(principal.id(), body.password());
        HttpSession current = request.getSession();
        accountSessions.invalidateAllExcept(principal.getUsername(), current.getId());
        // The current session is invalidated through the request so that Spring Session does not
        // save it again at the end of the request, and expires its cookie.
        current.invalidate();
        SecurityContextHolder.clearContext();
    }
}
