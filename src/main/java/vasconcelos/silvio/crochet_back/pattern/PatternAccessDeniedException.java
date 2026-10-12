package vasconcelos.silvio.crochet_back.pattern;

/** A public pattern of another account: visible, so refusing a write with 404 would be a lie. */
public class PatternAccessDeniedException extends RuntimeException {

    public PatternAccessDeniedException(long id) {
        super("Pattern " + id + " belongs to another account");
    }
}
