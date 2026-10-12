package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
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
        accountService.register(body.email(), body.password());
        AccountPrincipal principal = signIn(body.email(), body.password(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(accountService.get(principal.id())));
    }

    @PostMapping("/login")
    public AccountResponse login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        loginAttempts.acquireForIp(request.getRemoteAddr());
        // reserved before hashing, so a locked email costs no BCrypt work; unknown emails are counted
        // too, so a lock reveals nothing about which accounts exist
        loginAttempts.acquireForEmail(body.email());
        AccountPrincipal principal = signIn(body.email(), body.password(), request, response);
        loginAttempts.recordSuccess(body.email());
        return AccountResponse.from(accountService.get(principal.id()));
    }

    private AccountPrincipal signIn(
            String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation protection
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // a token an attacker planted before login must not survive it
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        return (AccountPrincipal) authentication.getPrincipal();
    }
}
