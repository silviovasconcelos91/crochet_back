package vasconcelos.silvio.crochet_back.account;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Login identifier form: surrounding whitespace removed, lowercase. {@code null} stays {@code null}. */
    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
