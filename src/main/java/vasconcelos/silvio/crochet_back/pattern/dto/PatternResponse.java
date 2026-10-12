package vasconcelos.silvio.crochet_back.pattern.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PatternResponse(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String sourceUrl,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt,
        List<StepResponse> steps,
        ProgressResponse progress) {
}
