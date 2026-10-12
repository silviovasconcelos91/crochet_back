package vasconcelos.silvio.crochet_back.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Session JDBC runs its SQL in a REQUIRES_NEW transaction unless a TransactionOperations bean
 * exists, which it then uses. This one joins the caller's transaction (REQUIRED): session deletions
 * commit or roll back with the change that caused them (a password change), and no second pool
 * connection is held while the first one waits. Outside any transaction (saving the session at the
 * end of a request) a new one is still started.
 */
@Configuration
public class SessionTransactionConfig {

    @Bean
    TransactionOperations springSessionTransactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
