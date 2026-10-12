package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;

    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AccountResponse.from(accountService.get(principal.id()));
    }
}
