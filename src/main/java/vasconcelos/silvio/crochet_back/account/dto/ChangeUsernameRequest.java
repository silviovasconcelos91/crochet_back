package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vasconcelos.silvio.crochet_back.account.Usernames;

public record ChangeUsernameRequest(@NotNull @Username String username, @NotBlank String currentPassword) {

    public ChangeUsernameRequest {
        username = Usernames.normalize(username);
    }
}
