package vasconcelos.silvio.crochet_back.account;

import java.util.List;

/** Registration or rename hit a unique field; {@code fields} are request field names ("username", "email"). */
public class AlreadyUsedException extends RuntimeException {

    private final List<String> fields;

    public AlreadyUsedException(List<String> fields) {
        super("Already used");
        this.fields = List.copyOf(fields);
    }

    public List<String> getFields() {
        return fields;
    }
}
