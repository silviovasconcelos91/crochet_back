package vasconcelos.silvio.crochet_back.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import vasconcelos.silvio.crochet_back.account.EmailAlreadyUsedException;
import vasconcelos.silvio.crochet_back.account.TooManyAttemptsException;
import vasconcelos.silvio.crochet_back.pattern.PatternNotFoundException;
import vasconcelos.silvio.crochet_back.pattern.ProgressNotFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Maps errors to RFC 9457 problem details. The base class already turns malformed JSON and
 * path-variable type mismatches into 400 problems.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PatternNotFoundException.class)
    ProblemDetail handleNotFound(PatternNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    /** The account store failed while authenticating: a server fault, not bad credentials. */
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    ProblemDetail handleAuthenticationServiceFailure(InternalAuthenticationServiceException ex) {
        logger.error("Authentication service failure", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    /** A session that reaches here is useless (e.g. its account was deleted): it is ended, not kept until idle timeout. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> handleTooManyAttempts(TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    ProblemDetail handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    /** A value of the wrong type or outside an enum names its field, like a validation error. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            Map<String, String> error = Map.of(
                    "field", fieldPath(mismatch.getPath()),
                    "message", mismatchMessage(mismatch));
            return handleExceptionInternal(ex, validationProblem(List.of(error)), headers, status, request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    /** Same notation as Bean Validation field paths, e.g. {@code steps[0].rows[1].value}. */
    private static String fieldPath(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                field.append(field.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return field.toString();
    }

    private static String mismatchMessage(MismatchedInputException ex) {
        Class<?> target = ex.getTargetType();
        if (target != null && target.isEnum()) {
            return "must be one of " + Arrays.toString(target.getEnumConstants());
        }
        return "has an invalid value";
    }

    @ExceptionHandler(ProgressNotFoundException.class)
    ProblemDetail handleProgressNotFound(ProgressNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidFieldException.class)
    ProblemDetail handleInvalidField(InvalidFieldException ex) {
        return validationProblem(List.of(Map.of("field", ex.getField(), "message", ex.getMessage())));
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}
