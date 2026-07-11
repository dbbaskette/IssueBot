package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueGuidance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface IssueGuidanceRepository extends JpaRepository<IssueGuidance, Long> {

    List<IssueGuidance> findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(Long issueId);

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
