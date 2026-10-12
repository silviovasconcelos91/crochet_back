package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.account.dto.BcryptPasswordValidator;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHintsTest {

    @Test
    void sessionPrincipalCanBeJavaSerialized() {
        RuntimeHints hints = new RuntimeHints();

        new NativeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.serialization().onType(AccountPrincipal.class)).accepts(hints);
    }

    @Test
    void customConstraintValidatorCanBeInstantiated() throws NoSuchMethodException {
        RuntimeHints hints = new RuntimeHints();

        new NativeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                .onConstructorInvocation(BcryptPasswordValidator.class.getDeclaredConstructor()))
                .accepts(hints);
    }

    @Test
    void loginFactorAuthorityCanBeJavaSerialized() {
        RuntimeHints hints = new RuntimeHints();

        new NativeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.serialization().onType(FactorGrantedAuthority.class)).accepts(hints);
        // its issue time: an Instant, serialized through its java.time.Ser replacement
        assertThat(RuntimeHintsPredicates.serialization().onType(Instant.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of("java.time.Ser"))).accepts(hints);
    }
}
