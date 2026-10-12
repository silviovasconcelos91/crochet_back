package vasconcelos.silvio.crochet_back.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.account.dto.BcryptPasswordValidator;

import java.time.Instant;

/**
 * What the native image cannot discover on its own, each entry found by running the smoke test
 * against the native binary:
 * <ul>
 *   <li>Spring Session JDBC stores the security context with Java serialization, so the principal it
 *   contains (and the login factor authority Spring Security adds) must be registered;</li>
 *   <li>Hibernate Validator instantiates custom constraint validators by reflection.</li>
 * </ul>
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.serialization().registerType(AccountPrincipal.class);
        hints.reflection().registerType(BcryptPasswordValidator.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        // Spring Security 7 adds the password factor (with its issue time) to every login
        hints.serialization().registerType(FactorGrantedAuthority.class);
        hints.serialization().registerType(Instant.class);
        hints.serialization().registerType(TypeReference.of("java.time.Ser"));
    }
}
