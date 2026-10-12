package vasconcelos.silvio.crochet_back.pattern;

import java.math.BigDecimal;
import java.time.Instant;

/** Flat result of the summary query; progress columns are null when the pattern is not started. */
public record PatternSummaryRow(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String imageUrl,
        Integer stepCount,
        Visibility visibility,
        ProgressStatus status,
        Integer stepPosition,
        Integer rowPosition,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt) {
}
