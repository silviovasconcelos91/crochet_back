package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return accounts.findByUsernameKey(Usernames.key(username))
                .map(account -> new AccountPrincipal(account.getId(), account.getPasswordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
