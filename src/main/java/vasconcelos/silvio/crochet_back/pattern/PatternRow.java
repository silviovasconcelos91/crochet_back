package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pattern_row")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private Step step;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 20)
    private String number;

    @Column(nullable = false)
    private String value;

    public PatternRow(int position, String number, String value) {
        this.position = position;
        this.number = number;
        this.value = value;
    }
}
