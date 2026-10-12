package vasconcelos.silvio.crochet_back.pattern.dto;

import vasconcelos.silvio.crochet_back.pattern.ProgressStatus;

import java.time.Instant;

public record ProgressResponse(
        ProgressStatus status,
        int stepIndex,
        int rowIndex,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt) {
}
