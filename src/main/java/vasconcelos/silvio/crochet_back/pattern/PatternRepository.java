package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

    /** Owner, or anyone for a public pattern. Writes check ownership on top (PatternService). */
    String READABLE = "p.id = :id and (p.ownerId = :accountId"
            + " or p.visibility = vasconcelos.silvio.crochet_back.pattern.Visibility.PUBLIC)";

    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadable(@Param("id") Long id, @Param("accountId") Long accountId);

    /** For a progress write: shared lock, so progress writes of different accounts run in parallel. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadableForShare(@Param("id") Long id, @Param("accountId") Long accountId);

    /** For a pattern write: exclusive lock, waits for the progress writes in flight and blocks new ones. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadableForUpdate(@Param("id") Long id, @Param("accountId") Long accountId);

    String SUMMARY = """
            select new vasconcelos.silvio.crochet_back.pattern.PatternSummaryRow(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps), p.visibility,
                p.ownerId, a.username,
                pr.status, pr.stepPosition, pr.rowPosition, pr.startedAt, pr.updatedAt, pr.finishedAt)
            from Pattern p
            join Account a on a.id = p.ownerId
            left join PatternProgress pr on pr.patternId = p.id and pr.accountId = :accountId
            """;

    @Query(SUMMARY + " where p.ownerId = :accountId order by lower(p.title), p.id")
    List<PatternSummaryRow> findAllSummaries(@Param("accountId") Long accountId);

    @Query(SUMMARY + " where p.visibility = vasconcelos.silvio.crochet_back.pattern.Visibility.PUBLIC"
            + " order by lower(p.title), p.id")
    List<PatternSummaryRow> findPublicSummaries(@Param("accountId") Long accountId);

    /** Read through the entity name: the pattern package has no Java dependency on account. */
    @Query("select a.username from Account a where a.id = :ownerId")
    String findAuthorUsername(@Param("ownerId") Long ownerId);
}
