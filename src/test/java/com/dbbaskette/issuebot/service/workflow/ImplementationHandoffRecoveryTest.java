package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImplementationHandoffRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyTheSameCompletedApprovedAttemptCanSkipCoding() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 8, "Approvals");
        PlanningVersion plan = mock(PlanningVersion.class);
        when(plan.getId()).thenReturn(16L);
        issue.setApprovedPlanningVersion(plan);
        issue.setStatus(IssueStatus.COOLDOWN);
        issue.setCurrentIteration(1);
        issue.setBranchName("issuebot/issue-8-existing-work");
        issue.setLastFailureReason("Coding harness blocked: Invalid implementation handoff: "
                + "Each check needs a bounded command and result");
        Iteration iteration = new Iteration(issue, 1, issue.getWorkflowRun(), 16L);
        iteration.setCompletedAt(LocalDateTime.now());
        iteration.setClaudeOutput("ISSUEBOT_IMPLEMENTATION_V1: "
                + "{\"status\":\"COMPLETE\",\"summary\":\"Done\",\"checks\":[],\"limitations\":\"\"}");

        assertThat(ImplementationHandoffRecovery.available(issue, iteration, mapper)).isTrue();
        iteration.setLocalCheckResult("PASSED");
        assertThat(ImplementationHandoffRecovery.available(issue, iteration, mapper)).isFalse();
        iteration.setLocalCheckResult(null);
        issue.setWorkflowRun(1);
        assertThat(ImplementationHandoffRecovery.available(issue, iteration, mapper)).isFalse();
    }
}
