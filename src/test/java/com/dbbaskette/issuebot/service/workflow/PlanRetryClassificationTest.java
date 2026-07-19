package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRetryClassificationTest {

    @Test
    void duplicateIterationNumbersUseNewestPersistedRowRegardlessOfInputOrder() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setPlanFirst(true);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Retry work");
        issue.setPlanConformanceAttempt(2);

        Iteration newestPass = review(issue, 2L, 2, true);
        Iteration olderMiss = review(issue, 1L, 2, false);

        assertThat(PlanRetryClassification.isSecondPlanFirstMiss(
                issue, List.of(newestPass, olderMiss))).isFalse();
    }

    private Iteration review(TrackedIssue issue, long id, int iterationNumber, boolean passed) {
        Iteration iteration = new Iteration(issue, iterationNumber);
        iteration.setId(id);
        iteration.setReviewPassed(passed);
        iteration.setReviewJson("{}");
        return iteration;
    }
}
