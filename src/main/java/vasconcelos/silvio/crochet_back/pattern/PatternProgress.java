package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.time.Instant;

/** Current step and row of a started pattern; positions are 0-based like Step/PatternRow positions. */
@Entity
@Table(name = "pattern_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternProgress {

    @Id
    @Column(name = "pattern_id")
    private Long patternId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pattern_id")
    private Pattern pattern;

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

    PatternProgress(Pattern pattern) {
        this.pattern = pattern;
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
}
