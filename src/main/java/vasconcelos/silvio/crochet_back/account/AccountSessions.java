package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /** Deletes every stored session of the account except {@code keptSessionId}. */
    public void invalidateAllExcept(String principalName, String keptSessionId) {
        sessions.findByPrincipalName(principalName).keySet().stream()
                .filter(sessionId -> !sessionId.equals(keptSessionId))
                .forEach(sessions::deleteById);
    }
}
