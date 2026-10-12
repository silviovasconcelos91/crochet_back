package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StepRequest(
        @NotBlank @Size(max = 200) String title,
        @NotEmpty List<@NotNull @Valid RowRequest> rows) {
}
