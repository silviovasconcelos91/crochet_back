package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vasconcelos.silvio.crochet_back.account.Usernames;

/** The username is bounded because its key becomes a key of the in-memory login throttle. */
public record LoginRequest(@NotBlank @Size(max = 254) String username, @NotBlank String password) {

    public LoginRequest {
        username = Usernames.normalize(username);
    }
}
