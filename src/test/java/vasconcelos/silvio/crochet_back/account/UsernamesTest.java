package vasconcelos.silvio.crochet_back.account;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vasconcelos.silvio.crochet_back.account.dto.Username;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {

    record Holder(@Username String username) {
    }

    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void normalizeStripsAndComposesAccents() {
        assertThat(Usernames.normalize("  Amélie ")).isEqualTo("Amélie");
        assertThat(Usernames.normalize(null)).isNull();
    }

    @Test
    void keyIgnoresCaseAndAccentEncoding() {
        assertThat(Usernames.key("AméLIE")).isEqualTo(Usernames.key(" amélie"));
        assertThat(Usernames.key("Amélie")).isEqualTo("amélie");
        assertThat(Usernames.key(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"moi", "Amélie_42", "a.b-c", "李小龍", "abcdefghijklmnopqrstuvwxyz1234"})
    void acceptsLettersDigitsAndSeparators(String username) {
        assertThat(validator.validate(new Holder(username))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxyz12345", "moi toi", "moi@exemple.fr", "moi!", ""})
    void rejectsOtherShapes(String username) {
        assertThat(validator.validate(new Holder(username)))
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage())
                        .isEqualTo("must be 3 to 30 letters, digits, '_', '-' or '.'"));
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(validator.validate(new Holder(null))).isEmpty();
    }
}
