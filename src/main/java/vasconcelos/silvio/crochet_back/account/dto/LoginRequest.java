package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vasconcelos.silvio.crochet_back.account.Emails;

/** The email is bounded because it becomes a key of the in-memory login throttle. */
public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
