package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vasconcelos.silvio.crochet_back.account.Emails;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @BcryptPassword String password) {

    public RegisterRequest {
        email = Emails.normalize(email);
    }
}
