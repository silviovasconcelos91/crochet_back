package vasconcelos.silvio.crochet_back.pattern.dto;

import vasconcelos.silvio.crochet_back.pattern.Visibility;

import java.math.BigDecimal;

public record PatternSummary(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String imageUrl,
        Integer stepCount,
        ProgressResponse progress,
        Visibility visibility) {
}
