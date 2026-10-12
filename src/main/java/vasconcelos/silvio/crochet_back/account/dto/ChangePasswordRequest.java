package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChangePasswordRequest(@NotBlank String currentPassword, @NotNull @BcryptPassword String newPassword) {
}
