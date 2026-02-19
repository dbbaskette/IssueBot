package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;

public interface CostTrackingRepository extends JpaRepository<CostTracking, Long> {

    List<CostTracking> findByIssue(TrackedIssue issue);

    @Query("SELECT COALESCE(SUM(c.estimatedCost), 0) FROM CostTracking c WHERE c.issue = :issue")
    BigDecimal totalCostForIssue(TrackedIssue issue);

    @Query("SELECT COALESCE(SUM(c.estimatedCost), 0) FROM CostTracking c WHERE c.issue = :issue AND c.phase = :phase")
    BigDecimal totalCostForIssueByPhase(TrackedIssue issue, String phase);

    @Query("SELECT COALESCE(SUM(c.estimatedCost), 0) FROM CostTracking c WHERE c.issue.repo = :repo")
    BigDecimal totalCostForRepo(WatchedRepo repo);

    @Query("SELECT COALESCE(SUM(c.inputTokens), 0) FROM CostTracking c")
    long totalInputTokens();

    @Query("SELECT COALESCE(SUM(c.outputTokens), 0) FROM CostTracking c")
    long totalOutputTokens();

    List<CostTracking> findByIssueRepoOrderByIdDesc(WatchedRepo repo);

    void deleteByIssue(TrackedIssue issue);
}
