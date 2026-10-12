package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import vasconcelos.silvio.crochet_back.account.Usernames;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** See {@link Usernames#FORMAT}. {@code null} is valid. */
@Pattern(regexp = Usernames.FORMAT)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
public @interface Username {

    String message() default "must be 3 to 30 letters, digits, '_', '-' or '.'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
