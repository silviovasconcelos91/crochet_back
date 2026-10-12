package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PatternProgressRepository extends JpaRepository<PatternProgress, PatternProgress.Key> {

    /**
     * Starts the progress at the first row if this account has none. A concurrent first write by the
     * same account (double click) waits for the other insert and then does nothing, instead of
     * failing on the primary key.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into pattern_progress
                (pattern_id, account_id, status, step_position, row_position, started_at, updated_at)
            values (:patternId, :accountId, 'IN_PROGRESS', 0, 0, :now, :now)
            on conflict do nothing
            """)
    int insertIfMissing(@Param("patternId") long patternId, @Param("accountId") long accountId,
                        @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pr from PatternProgress pr where pr.patternId = :patternId and pr.accountId = :accountId")
    Optional<PatternProgress> findForUpdate(@Param("patternId") long patternId, @Param("accountId") long accountId);

    @Modifying
    @Query("delete from PatternProgress pr where pr.patternId = :patternId and pr.accountId = :accountId")
    int deleteByKey(@Param("patternId") long patternId, @Param("accountId") long accountId);

    List<PatternProgress> findAllByPatternId(Long patternId);

    @Modifying
    @Query("delete from PatternProgress pr where pr.patternId = :patternId and pr.accountId <> :keptAccountId")
    int deleteOthers(@Param("patternId") long patternId, @Param("keptAccountId") long keptAccountId);
}
