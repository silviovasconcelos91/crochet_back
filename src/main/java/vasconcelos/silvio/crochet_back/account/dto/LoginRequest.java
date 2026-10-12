package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import vasconcelos.silvio.crochet_back.account.Emails;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
