package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vasconcelos.silvio.crochet_back.account.Emails;
import vasconcelos.silvio.crochet_back.account.Usernames;

public record RegisterRequest(
        @NotNull @Username String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @BcryptPassword String password) {

    public RegisterRequest {
        username = Usernames.normalize(username);
        email = Emails.normalize(email);
    }
}
