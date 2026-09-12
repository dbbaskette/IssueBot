package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueGuidance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface IssueGuidanceRepository extends JpaRepository<IssueGuidance, Long> {
    java.util.Optional<IssueGuidance> findByIssueIdAndRequestToken(Long issueId, String requestToken);

    List<IssueGuidance> findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(Long issueId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM IssueGuidance g WHERE g.issueId = :issueId "
            + "AND g.consumedAt IS NULL ORDER BY g.createdAt ASC, g.id ASC")
    List<IssueGuidance> findUnconsumedForUpdate(@Param("issueId") Long issueId);

    /**
     * Retire all unconsumed guidance for an issue with a targeted UPDATE —
     * never a full-entity save, so concurrent inserts/saves elsewhere can't
     * be clobbered. Transactional here because callers (workflow thread,
     * controller) are not.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IssueGuidance g SET g.consumedAt = :now WHERE g.issueId = :issueId AND g.consumedAt IS NULL")
    int markConsumed(@Param("issueId") Long issueId, @Param("now") LocalDateTime now);
}
