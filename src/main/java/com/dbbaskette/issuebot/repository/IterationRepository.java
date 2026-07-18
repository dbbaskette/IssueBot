package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface IterationRepository extends JpaRepository<Iteration, Long> {

    List<Iteration> findByIssueOrderByIterationNumAsc(TrackedIssue issue);

    /** Stable current-row lookup when guided retries reuse iteration numbers. */
    Optional<Iteration> findFirstByIssueIdAndIterationNumOrderByIdDesc(
            Long issueId, int iterationNum);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Iteration i WHERE i.id = :id")
    Optional<Iteration> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Iteration i WHERE i.id = (SELECT MAX(i2.id) FROM Iteration i2 "
            + "WHERE i2.issue.id = :issueId AND i2.iterationNum = :iterationNum)")
    Optional<Iteration> findCurrentForUpdate(@Param("issueId") Long issueId,
                                             @Param("iterationNum") int iterationNum);

    void deleteByIssue(TrackedIssue issue);
}
