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
import vasconcelos.silvio.crochet_back.account.dto.ChangeUsernameRequest;
import vasconcelos.silvio.crochet_back.account.dto.DeleteAccountRequest;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;
    private final AccountSessions accountSessions;
    private final LoginAttempts loginAttempts;

    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AccountResponse.from(accountService.get(principal.id()));
    }

    @PutMapping("/username")
    public AccountResponse changeUsername(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody ChangeUsernameRequest body) {
        return AccountResponse.from(accountService.changeUsername(principal.id(), body.username()));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        // throttled: a stolen session must not become a way to guess the password
        String sessionId = request.getSession().getId();
        loginAttempts.checkFromSession(throttleKey(principal), request.getRemoteAddr(), InvalidFieldException.class,
                () -> accountService.changePassword(
                        principal.id(), body.currentPassword(), body.newPassword(), sessionId));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest body,
            HttpServletRequest request) {
        String key = throttleKey(principal); // before the delete: the account is gone afterwards
        loginAttempts.checkFromSession(key, request.getRemoteAddr(), InvalidFieldException.class,
                () -> accountService.delete(principal.id(), body.password()));
        HttpSession current = request.getSession();
        accountSessions.invalidateAllExcept(principal.getUsername(), current.getId());
        // The current session is invalidated through the request so that Spring Session does not
        // save it again at the end of the request, and expires its cookie.
        current.invalidate();
        SecurityContextHolder.clearContext();
    }

    /** The same key as the login's throttle; read from the account because the principal's name is its id. */
    private String throttleKey(AccountPrincipal principal) {
        return accountService.get(principal.id()).getUsernameKey();
    }
}
