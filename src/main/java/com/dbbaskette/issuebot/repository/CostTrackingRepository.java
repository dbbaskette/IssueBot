package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;

public interface CostTrackingRepository extends JpaRepository<CostTracking, Long> {

    List<CostTracking> findByIssue(TrackedIssue issue);

    @Query("SELECT COALESCE(SUM(c.estimatedCost), 0) FROM CostTracking c WHERE c.issue = :issue")
    BigDecimal totalCostForIssue(TrackedIssue issue);
}
