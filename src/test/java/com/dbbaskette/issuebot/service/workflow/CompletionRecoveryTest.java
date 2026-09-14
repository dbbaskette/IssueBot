package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompletionRecoveryTest {
    @Test
    void onlyTheSameReviewedMergeFailureCanResumeWithoutCoding() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 9, "Triggers");
        issue.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        PlanningVersion plan = mock(PlanningVersion.class);
        when(plan.getId()).thenReturn(16L);
        issue.setApprovedPlanningVersion(plan);
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase("COMPLETION");
        issue.setCurrentIteration(2);
        issue.setBranchName("issuebot/issue-9-reviewed");
        issue.setPrNumber(29);
        issue.setLastFailureReason("Completion failed: Managed merge failed: GitHub checks did not finish");
        Iteration iteration = new Iteration(issue, 2, issue.getWorkflowRun(), 16L);
        iteration.setReviewPassed(true);
        iteration.setLocalCheckResult("PASSED");
        iteration.setCiResult("SKIPPED");
        iteration.setReviewedCommitSha("a".repeat(40));

        assertThat(CompletionRecovery.available(issue, iteration)).isTrue();
        iteration.setLocalCheckResult("REPORTED");
        assertThat(CompletionRecovery.available(issue, iteration)).isFalse();
        iteration.setHarnessVerificationEvidence("Agent reported: npm test — passed");
        assertThat(CompletionRecovery.available(issue, iteration)).isTrue();
        iteration.setLocalCheckResult("NOT_RUN");
        assertThat(CompletionRecovery.available(issue, iteration)).isTrue();
        iteration.setLocalCheckResult("FAILED");
        assertThat(CompletionRecovery.available(issue, iteration)).isFalse();
        iteration.setLocalCheckResult("REPORTED");
        iteration.setReviewPassed(false);
        assertThat(CompletionRecovery.available(issue, iteration)).isFalse();
        iteration.setReviewPassed(true);
        issue.setWorkflowRun(1);
        assertThat(CompletionRecovery.available(issue, iteration)).isFalse();
    }
}
