package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record PatternRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        @Positive @Digits(integer = 2, fraction = 2) BigDecimal hookSizeMm,
        @Size(max = 200) String yarn,
        @Size(max = 500) String sourceUrl,
        @Size(max = 500) String imageUrl,
        @NotEmpty List<@NotNull @Valid StepRequest> steps) {
}
