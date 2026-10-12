package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps))
            from Pattern p
            order by lower(p.title), p.id
            """)
    List<PatternSummary> findAllSummaries();
}
