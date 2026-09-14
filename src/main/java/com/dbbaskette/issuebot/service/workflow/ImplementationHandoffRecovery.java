package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Narrow recovery of a completed coding result rejected only by an older handoff parser. */
public final class ImplementationHandoffRecovery {
    private static final String FAILURE_PREFIX =
            "Coding harness blocked: Invalid implementation handoff:";

    private ImplementationHandoffRecovery() {}

    public static boolean available(TrackedIssue issue, Iteration iteration, ObjectMapper mapper) {
        try {
            requireComplete(issue, iteration, mapper);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static ImplementationOutcome requireComplete(
            TrackedIssue issue, Iteration iteration, ObjectMapper mapper) {
        if (issue == null || iteration == null || mapper == null
                || (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN)
                || issue.getLastFailureReason() == null
                || !issue.getLastFailureReason().startsWith(FAILURE_PREFIX)) {
            throw new IllegalArgumentException("This issue was not stopped by an invalid coding handoff");
        }
        if (issue.getApprovedPlanningVersion() == null
                || !iteration.matchesAttemptIdentity(issue.getWorkflowRun(),
                        issue.getApprovedPlanningVersion().getId())
                || iteration.getIterationNum() != issue.getCurrentIteration()
                || issue.getBranchName() == null || issue.getBranchName().isBlank()
                || issue.getPrNumber() != null) {
            throw new IllegalArgumentException("The saved coding result does not match this approved attempt");
        }
        if (iteration.getCompletedAt() == null || iteration.getImplementationCompletedAt() != null
                || iteration.getImplementationTurnCount() != 0
                || iteration.getLocalCheckResult() != null || iteration.getCiResult() != null
                || iteration.getReviewJson() != null || iteration.getClaudeOutput() == null) {
            throw new IllegalArgumentException("This attempt has later evidence or a saved coding-turn history");
        }
        HarnessExecutionResult saved = new HarnessExecutionResult();
        saved.setSuccess(true);
        saved.setFinalResult(iteration.getClaudeOutput());
        ImplementationOutcome outcome = ImplementationOutcome.parse(saved, mapper);
        if (outcome.status() != ImplementationOutcome.Status.COMPLETE) {
            throw new IllegalArgumentException("The saved coding result did not claim completion");
        }
        return outcome;
    }
}
