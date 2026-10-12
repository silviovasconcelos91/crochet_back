package vasconcelos.silvio.crochet_back.account;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The single place usernames are compared: uniqueness, login lookup and the login throttle all use
 * {@link #key}, so they can never disagree on whether two spellings are the same name.
 */
public final class Usernames {

    /** Letters (accents included), digits, '_', '-', '.'; no space and no '@', so never an email. */
    public static final String FORMAT = "^[\\p{L}\\p{N}_.-]{3,30}$";

    private Usernames() {
    }

    /** Display form: surrounding whitespace removed, NFC (one encoding per accent). {@code null} stays {@code null}. */
    public static String normalize(String username) {
        return username == null ? null : Normalizer.normalize(username.strip(), Normalizer.Form.NFC);
    }

    /** Comparison form: {@link #normalize}d, lowercase, NFC again (lowercasing can decompose). */
    public static String key(String username) {
        String normalized = normalize(username);
        return normalized == null ? null
                : Normalizer.normalize(normalized.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
