package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;

/** Resume a reviewed, unchanged pull request after an operational merge failure. */
public final class CompletionRecovery {
    private CompletionRecovery() {}

    public static boolean available(TrackedIssue issue, Iteration iteration) {
        try {
            requireReviewedAttempt(issue, iteration);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static void requireReviewedAttempt(TrackedIssue issue, Iteration iteration) {
        if (issue == null || iteration == null || issue.getStatus() != IssueStatus.FAILED
                || !"COMPLETION".equals(issue.getCurrentPhase())
                || issue.getLastFailureReason() == null
                || !issue.getLastFailureReason().startsWith("Completion failed: Managed merge failed:")
                || !StageWorkflowCoordinator.managed(issue)) {
            throw new IllegalArgumentException("This issue has no reviewed merge to resume");
        }
        if (issue.getApprovedPlanningVersion() == null
                || !iteration.matchesAttemptIdentity(issue.getWorkflowRun(),
                        issue.getApprovedPlanningVersion().getId())
                || iteration.getIterationNum() != issue.getCurrentIteration()
                || !Boolean.TRUE.equals(iteration.getReviewPassed())
                || !"PASSED".equals(iteration.getLocalCheckResult())
                || !("PASSED".equals(iteration.getCiResult())
                        || "SKIPPED".equals(iteration.getCiResult()))
                || iteration.getReviewedCommitSha() == null
                || !iteration.getReviewedCommitSha().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                || issue.getPrNumber() == null || issue.getPrNumber() <= 0
                || issue.getBranchName() == null || issue.getBranchName().isBlank()) {
            throw new IllegalArgumentException("The saved review, commit, and pull request do not match this attempt");
        }
    }
}
