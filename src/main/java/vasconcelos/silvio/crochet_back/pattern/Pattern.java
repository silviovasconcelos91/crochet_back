package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pattern")
@Getter
@Setter
@NoArgsConstructor
public class Pattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Column(name = "hook_size_mm", precision = 4, scale = 2)
    private BigDecimal hookSizeMm;

    @Column(length = 200)
    private String yarn;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @Setter(AccessLevel.NONE)
    private List<Step> steps = new ArrayList<>();

    @OneToOne(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private PatternProgress progress;

    public void addStep(Step step) {
        step.setPattern(this);
        steps.add(step);
    }

    /** Starts the pattern if needed, then moves its progress. Bounds are checked by the caller. */
    public PatternProgress updateProgress(ProgressStatus status, int stepPosition, int rowPosition) {
        if (progress == null) {
            progress = new PatternProgress(this);
        }
        progress.update(status, stepPosition, rowPosition);
        return progress;
    }

    /** Abandons the pattern: its progress row is deleted (orphan removal). */
    public void clearProgress() {
        progress = null;
    }

    /** Marks the pattern as modified; call on every replace, even when only steps/rows changed. */
    public void touch() {
        updatedAt = Timestamps.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Timestamps.now();
        createdAt = now;
        updatedAt = now;
    }
}
