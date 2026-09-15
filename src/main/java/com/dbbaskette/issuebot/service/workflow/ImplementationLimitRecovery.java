package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;

/** Only an exhausted, retained coding attempt can receive more handoffs. */
public final class ImplementationLimitRecovery {
    private ImplementationLimitRecovery() {}

    public static boolean available(TrackedIssue issue, Iteration iteration) {
        return issue != null && iteration != null
                && (issue.getStatus() == IssueStatus.FAILED || issue.getStatus() == IssueStatus.COOLDOWN)
                && ("HANDOFF_LIMIT".equals(iteration.getImplementationStopReason())
                    || "INVOCATION_TIMEOUT".equals(iteration.getImplementationStopReason()))
                && "CONTINUE".equals(iteration.getImplementationOutcome())
                && iteration.getImplementationCompletedAt() == null
                && iteration.getIterationNum() == issue.getCurrentIteration()
                && iteration.matchesAttemptIdentity(issue.getWorkflowRun(),
                    issue.getApprovedPlanningVersion() == null ? null : issue.getApprovedPlanningVersion().getId())
                && iteration.getImplementationHandoffLimit() != null
                && iteration.getImplementationHandoffLimit() < 100
                && iteration.getClaudeSessionId() != null && !iteration.getClaudeSessionId().isBlank()
                && issue.getBranchName() != null && !issue.getBranchName().isBlank();
    }
}
