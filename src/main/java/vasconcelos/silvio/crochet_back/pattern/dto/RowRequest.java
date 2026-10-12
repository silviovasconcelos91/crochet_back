package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RowRequest(
        @NotBlank @Size(max = 20) String number,
        @NotBlank String value) {
}
