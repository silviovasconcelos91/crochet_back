package vasconcelos.silvio.crochet_back.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;

/**
 * What the native image cannot discover on its own. Spring Session JDBC stores the security context
 * with Java serialization, so the principal it contains must be registered for serialization.
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.serialization().registerType(AccountPrincipal.class);
    }
}
