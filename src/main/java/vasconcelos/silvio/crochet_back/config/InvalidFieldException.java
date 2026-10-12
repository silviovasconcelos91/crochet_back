package vasconcelos.silvio.crochet_back.config;

/** A request field that is well-formed but rejected by a business rule; rendered as a 400 field error. */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
