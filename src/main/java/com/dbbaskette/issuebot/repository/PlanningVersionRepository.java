package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanningVersionRepository extends JpaRepository<PlanningVersion, Long> {

    List<PlanningVersion> findByIssueIdOrderByVersionNumberDesc(Long issueId);

    Optional<PlanningVersion> findFirstByIssueIdOrderByVersionNumberDesc(Long issueId);

    /** Latest immutable planning row under the same write lock as its owning issue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlanningVersion p WHERE p.issue.id = :issueId "
            + "ORDER BY p.versionNumber DESC")
    List<PlanningVersion> findLatestByIssueIdForUpdate(@Param("issueId") Long issueId);

    Optional<PlanningVersion> findByIssueIdAndVersionNumber(Long issueId, int versionNumber);

    List<PlanningVersion> findByIssueIdInAndState(Collection<Long> issueIds, PlanningVersionState state);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM PlanningVersion p WHERE p.issue.id IN :issueIds")
    int deleteByIssueIds(@Param("issueIds") Collection<Long> issueIds);
}
