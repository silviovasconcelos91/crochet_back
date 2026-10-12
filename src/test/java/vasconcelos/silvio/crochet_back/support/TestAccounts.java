package vasconcelos.silvio.crochet_back.support;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.account.Account;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.account.AccountRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public final class TestAccounts {

    public static final String PASSWORD = "motdepasse-secret";

    private TestAccounts() {
    }

    /** Inserts a real account row and returns the principal Spring Security builds for it. */
    public static AccountPrincipal create(AccountRepository accounts, PasswordEncoder encoder, String email) {
        Account account = accounts.save(new Account(email, encoder.encode(PASSWORD)));
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash());
    }

    /** MockMvc whose every request is authenticated as {@code principal} and carries a valid CSRF token. */
    public static MockMvc mockMvcAs(WebApplicationContext context, AccountPrincipal principal) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(principal)).with(csrf()))
                .build();
    }
}
