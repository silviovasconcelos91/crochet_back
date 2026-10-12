package vasconcelos.silvio.crochet_back.pattern;

public class PatternNotFoundException extends RuntimeException {

    public PatternNotFoundException(long id) {
        super("Pattern " + id + " not found");
    }
}
