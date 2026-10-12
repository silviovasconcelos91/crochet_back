package vasconcelos.silvio.crochet_back.account;

public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException() {
        super("Email already used");
    }
}
