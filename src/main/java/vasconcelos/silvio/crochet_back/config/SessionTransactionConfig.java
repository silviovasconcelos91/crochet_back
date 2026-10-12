package vasconcelos.silvio.crochet_back.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Session JDBC runs its SQL in a REQUIRES_NEW transaction unless it is given a
 * TransactionOperations bean named {@code springSessionTransactionOperations}. This one joins the
 * caller's transaction (REQUIRED): session deletions commit or roll back with the change that caused
 * them (a password change), and no second pool connection is held while the first one waits. Outside any transaction (saving the session at the
 * end of a request) a new one is still started.
 *
 * <p>It also stands in for Boot's {@code transactionTemplate} bean (conditional on no
 * TransactionOperations bean), with the same defaults, so it is typed as a TransactionTemplate.
 */
@Configuration
public class SessionTransactionConfig {

    @Bean
    TransactionTemplate springSessionTransactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
