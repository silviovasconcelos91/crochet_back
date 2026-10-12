package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

    @Query("select p from Pattern p left join fetch p.progress where p.id = :id and p.ownerId = :ownerId")
    Optional<Pattern> findByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);

    /**
     * Same as {@link #findByIdAndOwnerId} but locks the pattern row until the transaction ends, so
     * that concurrent writes to one pattern's progress run one after the other. Postgres cannot lock
     * the nullable side of an outer join, so the progress comes from a second select, issued once the
     * lock is held and therefore seeing what the previous writer committed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pattern p where p.id = :id and p.ownerId = :ownerId")
    Optional<Pattern> findByIdAndOwnerIdForUpdate(@Param("id") Long id, @Param("ownerId") Long ownerId);

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
