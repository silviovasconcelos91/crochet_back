package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * One account's current step and row on one pattern; positions are 0-based like Step/PatternRow
 * positions. Not part of the Pattern aggregate: several accounts follow one public pattern.
 */
@Entity
@Table(name = "pattern_progress")
@IdClass(PatternProgress.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternProgress {

    @Id
    @Column(name = "pattern_id")
    private Long patternId;

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProgressStatus status;

    @Column(name = "step_position", nullable = false)
    private int stepPosition;

    @Column(name = "row_position", nullable = false)
    private int rowPosition;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    PatternProgress(Long patternId, Long accountId) {
        this.patternId = patternId;
        this.accountId = accountId;
        this.startedAt = Timestamps.now();
    }

    /** finishedAt is set on the transition to FINISHED only, and cleared when reopened. */
    void update(ProgressStatus newStatus, int newStepPosition, int newRowPosition) {
        if (newStatus == ProgressStatus.FINISHED && status != ProgressStatus.FINISHED) {
            finishedAt = Timestamps.now();
        } else if (newStatus == ProgressStatus.IN_PROGRESS) {
            finishedAt = null;
        }
        status = newStatus;
        stepPosition = newStepPosition;
        rowPosition = newRowPosition;
        updatedAt = Timestamps.now();
    }

    /**
     * Moves the position back inside {@code steps} after the pattern was replaced; status, startedAt
     * and updatedAt are left unchanged because the user did not move.
     */
    void clampTo(List<Step> steps) {
        stepPosition = Math.min(stepPosition, steps.size() - 1);
        rowPosition = Math.min(rowPosition, steps.get(stepPosition).getRows().size() - 1);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long patternId;
        private Long accountId;
    }
}
