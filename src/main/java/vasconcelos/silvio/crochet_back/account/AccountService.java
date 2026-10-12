package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final AccountSessions accountSessions;

    /** {@code email} must already be normalized. */
    @Transactional
    public Account register(String email, String rawPassword) {
        if (accounts.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        try {
            return accounts.saveAndFlush(new Account(email, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException concurrentRegistration) {
            throw new EmailAlreadyUsedException();
        }
    }

    /** Loads the logged-in account; a session outliving its account is treated as unauthenticated. */
    public Account get(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new InsufficientAuthenticationException("Account no longer exists"));
    }

    /**
     * Ends every other session of the account inside the password transaction. Spring Session deletes
     * them in its own (REQUIRES_NEW) transaction: if that fails, the new password rolls back; if the
     * password commit fails afterwards, the sessions are gone but the old password stays. Either way no
     * other session survives a password that was actually changed.
     */
    @Transactional
    public void changePassword(long id, String currentPassword, String newPassword, String keptSessionId) {
        Account account = get(id);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new InvalidFieldException("currentPassword", "is incorrect");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accounts.flush();
        accountSessions.invalidateAllExcept(account.getEmail(), keptSessionId);
    }

    /** Patterns, steps, rows and progress go with the account through ON DELETE CASCADE. */
    @Transactional
    public void delete(long id, String password) {
        Account account = get(id);
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new InvalidFieldException("password", "is incorrect");
        }
        accounts.delete(account);
    }
}
