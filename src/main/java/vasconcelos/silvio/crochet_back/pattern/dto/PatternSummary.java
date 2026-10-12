package vasconcelos.silvio.crochet_back.pattern.dto;

import java.math.BigDecimal;

public record PatternSummary(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String imageUrl,
        Integer stepCount,
        ProgressResponse progress) {
}
