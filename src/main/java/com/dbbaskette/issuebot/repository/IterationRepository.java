package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface IterationRepository extends JpaRepository<Iteration, Long> {

    List<Iteration> findByIssueOrderByIterationNumAsc(TrackedIssue issue);

    /** Stable current-row lookup when guided retries reuse iteration numbers. */
    Optional<Iteration> findFirstByIssueIdAndIterationNumOrderByIdDesc(
            Long issueId, int iterationNum);

    void deleteByIssue(TrackedIssue issue);
}
