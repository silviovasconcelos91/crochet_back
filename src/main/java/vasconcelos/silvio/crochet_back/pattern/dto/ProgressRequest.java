package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vasconcelos.silvio.crochet_back.pattern.ProgressStatus;

public record ProgressRequest(
        @NotNull ProgressStatus status,
        @NotNull @PositiveOrZero Integer stepIndex,
        @NotNull @PositiveOrZero Integer rowIndex) {
}
