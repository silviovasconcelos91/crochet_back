package vasconcelos.silvio.crochet_back.pattern;

public class ProgressNotFoundException extends RuntimeException {

    public ProgressNotFoundException(long patternId) {
        super("Pattern " + patternId + " is not started");
    }
}
