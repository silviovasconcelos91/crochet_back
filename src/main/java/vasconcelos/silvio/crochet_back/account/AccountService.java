package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final AccountSessions accountSessions;

    /** {@code username} and {@code email} must already be normalized. */
    @Transactional
    public Account register(String username, String email, String rawPassword) {
        List<String> taken = new ArrayList<>();
        if (accounts.existsByUsernameKey(Usernames.key(username))) {
            taken.add("username");
        }
        if (accounts.existsByEmail(email)) {
            taken.add("email");
        }
        if (!taken.isEmpty()) {
            throw new AlreadyUsedException(taken);
        }
        try {
            return accounts.saveAndFlush(new Account(username, email, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException ex) {
            // registered concurrently
            if (violates(ex, "uk_account_username_key")) {
                throw new AlreadyUsedException(List.of("username"));
            }
            if (violates(ex, "uk_account_email")) {
                throw new AlreadyUsedException(List.of("email"));
            }
            throw ex;
        }
    }

    private static boolean violates(DataIntegrityViolationException ex, String constraint) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    /** Loads the logged-in account; a session outliving its account is treated as unauthenticated. */
    public Account get(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new InsufficientAuthenticationException("Account no longer exists"));
    }

    /** {@code username} must already be normalized. The sessions survive: their principal is the id. */
    @Transactional
    public Account changeUsername(long id, String username) {
        Account account = get(id);
        String key = Usernames.key(username);
        if (!key.equals(account.getUsernameKey()) && accounts.existsByUsernameKey(key)) {
            throw new AlreadyUsedException(List.of("username"));
        }
        account.rename(username);
        try {
            accounts.flush();
        } catch (DataIntegrityViolationException ex) {
            if (violates(ex, "uk_account_username_key")) {
                throw new AlreadyUsedException(List.of("username")); // taken concurrently
            }
            throw ex;
        }
        return account;
    }

    /**
     * Ends every other session of the account in the same transaction as the new password (see
     * SessionTransactionConfig): both are committed together or not at all.
     */
    @Transactional
    public void changePassword(long id, String currentPassword, String newPassword, String keptSessionId) {
        Account account = get(id);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new InvalidFieldException("currentPassword", "is incorrect");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accountSessions.invalidateAllExcept(String.valueOf(account.getId()), keptSessionId);
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
