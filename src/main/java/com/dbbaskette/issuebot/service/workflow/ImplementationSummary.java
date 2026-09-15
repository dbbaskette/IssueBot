package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Presentation only: agent claims never change acceptance or workflow state. */
public record ImplementationSummary(String label, String css, String message,
                                    String limitations, List<ImplementationOutcome.Check> checks) {
    public static ImplementationSummary from(TrackedIssue issue, Iteration iteration, ObjectMapper mapper) {
        boolean active = issue.getStatus() == IssueStatus.IN_PROGRESS
                && "IMPLEMENTATION".equals(issue.getCurrentPhase());
        ImplementationOutcome outcome = null;
        if (iteration != null && iteration.getClaudeOutput() != null) {
            var result = new HarnessExecutionResult();
            result.setSuccess(true);
            result.setFinalResult(iteration.getClaudeOutput());
            try { outcome = ImplementationOutcome.parse(result, mapper); }
            catch (IllegalArgumentException ignored) { /* Older/free-text results remain available below. */ }
        }
        String status = iteration == null ? null : iteration.getImplementationOutcome();
        if (status == null && outcome != null) status = outcome.status().name();
        String label;
        String css;
        String message;
        if (iteration != null && Boolean.TRUE.equals(iteration.getImplementationSucceeded())) {
            label = "Finished"; css = "status-completed"; message = "Coding finished for the latest attempt.";
        } else if (active) {
            label = "Working"; css = "status-in_progress"; message = "The coding agent is working.";
        } else if ("BLOCKED".equals(status)) {
            label = "Blocked"; css = "status-failed";
            message = "Implementation attempt retained; verification is incomplete. Resolve the reported blocker before continuing.";
        } else if (iteration != null && (iteration.getClaudeOutput() != null || status != null)) {
            label = "Incomplete"; css = "status-pending";
            message = "This attempt has saved coding output but has not completed implementation.";
        } else {
            label = "Not started"; css = "status-pending"; message = "Coding has not started.";
        }
        return new ImplementationSummary(label, css,
                outcome == null || active ? message : message + " " + outcome.summary(),
                outcome == null ? null : outcome.limitations(),
                outcome == null ? List.of() : outcome.checks());
    }
}
