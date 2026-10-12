package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

    Optional<Pattern> findByIdAndOwnerId(Long id, Long ownerId);

    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.PatternSummaryRow(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps),
                pr.status, pr.stepPosition, pr.rowPosition, pr.startedAt, pr.updatedAt, pr.finishedAt)
            from Pattern p left join p.progress pr
            where p.ownerId = :ownerId
            order by lower(p.title), p.id
            """)
    List<PatternSummaryRow> findAllSummaries(@Param("ownerId") Long ownerId);
}
