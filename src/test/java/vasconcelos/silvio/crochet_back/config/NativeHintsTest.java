package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHintsTest {

    @Test
    void sessionPrincipalCanBeJavaSerialized() {
        RuntimeHints hints = new RuntimeHints();

        new NativeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.serialization().onType(AccountPrincipal.class)).accepts(hints);
    }
}
