package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;
import vasconcelos.silvio.crochet_back.account.dto.LoginRequest;
import vasconcelos.silvio.crochet_back.account.dto.RegisterRequest;

/** Logout is handled by Spring Security's logout filter (see SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginAttempts loginAttempts;

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken token) {
        // Reading the deferred token makes the repository write the XSRF-TOKEN cookie.
        token.getToken();
    }

    @PostMapping("/register")
    public ResponseEntity<AccountResponse> register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request, HttpServletResponse response) {
        loginAttempts.acquireForIp(request.getRemoteAddr());
        Account account = accountService.register(body.username(), body.email(), body.password());
        // the password was just hashed: checking it again through the AuthenticationManager would
        // only cost a second BCrypt round
        AccountPrincipal principal = new AccountPrincipal(account.getId(), null);
        startSession(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @PostMapping("/login")
    public AccountResponse login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        loginAttempts.acquireForIp(request.getRemoteAddr());
        // reserved before hashing, so a locked username costs no BCrypt work; unknown usernames are
        // counted too, so a lock reveals nothing about which accounts exist
        Authentication authentication = loginAttempts.checkLogin(
                Usernames.key(body.username()), request.getRemoteAddr(), BadCredentialsException.class,
                () -> authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password())));
        startSession(authentication, request, response);
        return AccountResponse.from(accountService.get(((AccountPrincipal) authentication.getPrincipal()).id()));
    }

    private void startSession(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation protection
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // a token an attacker planted before login must not survive it
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }
}
