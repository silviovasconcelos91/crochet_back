package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class BcryptPasswordValidator implements ConstraintValidator<BcryptPassword, String> {

    static final int MIN_CHARACTERS = 8;
    static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.codePointCount(0, value.length()) >= MIN_CHARACTERS
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
