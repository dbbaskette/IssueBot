package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageWorkflowCoordinatorTest {
    StageApprovalService stages = mock(StageApprovalService.class);
    StageModelSelectionService models = mock(StageModelSelectionService.class);
    CodingHarnessService agent = mock(CodingHarnessService.class);
    TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    PlanningVersionRepository versions = mock(PlanningVersionRepository.class);
    PlanFirstTransactionManager plans = mock(PlanFirstTransactionManager.class);
    IssueDispatchService dispatch = mock(IssueDispatchService.class);
    StageWorkflowCoordinator coordinator = new StageWorkflowCoordinator(stages, models, agent,
            issues, versions, plans, dispatch);
    TrackedIssue issue = issue();

    private TrackedIssue issue() {
        TrackedIssue value = new TrackedIssue(new WatchedRepo("owner", "repo"), 1, "title");
        value.setId(10L);
        value.setWorkflowPolicy(WorkflowPolicy.STAGED);
        return value;
    }

    @Test void legacyAndUnsnappedIssuesDoNotCreateGate() {
        issue.setWorkflowPolicy(WorkflowPolicy.LEGACY);
        assertThat(coordinator.before(issue, WorkflowStage.PLANNING, 1)).isTrue();
        issue.setWorkflowPolicy(null);
        assertThat(coordinator.before(issue, WorkflowStage.PLANNING, 1)).isTrue();
        verifyNoInteractions(stages, agent, models);
    }

    @Test void waitingDoesNotSelectOrPinExecutionModel() {
        when(stages.beforeStage(issue, WorkflowStage.IMPLEMENTATION, 1)).thenReturn(new StageApproval());
        assertThat(coordinator.before(issue, WorkflowStage.IMPLEMENTATION, 1)).isFalse();
        verifyNoInteractions(agent, models, issues);
    }

    @Test void approvedPlanningAndImplementationPinSubscriptionAndStoreSelection() {
        for (WorkflowStage stage : List.of(WorkflowStage.PLANNING, WorkflowStage.IMPLEMENTATION)) {
            approved(stage);
            issue.setResolvedAgentProvider(AgentProvider.CLAUDE_CODE);
            issue.setClaudeSessionId("old-session");
            assertThat(coordinator.before(issue, stage, 1)).isTrue();
            assertThat(issue.getResolvedAgentProvider()).isEqualTo(AgentProvider.CODEX);
            assertThat(issue.getResolvedImplModel()).isEqualTo("selected-model");
            if (stage == WorkflowStage.IMPLEMENTATION) assertThat(issue.getClaudeSessionId()).isNull();
        }
        verify(agent, times(2)).pinSubscriptionHarness("codex");
        verify(models, never()).validate(any());
    }

    @Test void reviewSelectionPreservesImplementationProvenance() {
        approved(WorkflowStage.REVIEW);
        issue.setResolvedAgentProvider(AgentProvider.CLAUDE_CODE);
        issue.setResolvedImplModel("implementation-model");
        issue.setClaudeSessionId("implementation-session");
        assertThat(coordinator.before(issue, WorkflowStage.REVIEW, 1)).isTrue();
        assertThat(issue.getResolvedReviewModel()).isEqualTo("selected-model");
        assertThat(issue.getResolvedImplModel()).isEqualTo("implementation-model");
        assertThat(issue.getResolvedAgentProvider()).isEqualTo(AgentProvider.CLAUDE_CODE);
        assertThat(issue.getClaudeSessionId()).isEqualTo("implementation-session");
        verify(agent).pinSubscriptionHarness("codex");
    }

    @Test void deterministicStagesDoNotPinModels() {
        approved(WorkflowStage.VERIFICATION);
        assertThat(coordinator.before(issue, WorkflowStage.VERIFICATION, 1)).isTrue();
        verifyNoInteractions(agent, models);
    }

    @Test void planningAttemptFollowsHighestImmutableVersionNumber() {
        when(versions.findByIssueIdOrderByVersionNumberDesc(10L)).thenReturn(List.of());
        assertThat(coordinator.planningAttempt(issue)).isEqualTo(1);
        when(versions.findByIssueIdOrderByVersionNumberDesc(10L)).thenReturn(List.of(
                PlanningVersion.pending(issue, 2, "spec", "plan", "provider", "model", null),
                PlanningVersion.pending(issue, 7, "spec", "plan", "provider", "model", null)));
        assertThat(coordinator.planningAttempt(issue)).isEqualTo(8);
    }

    @Test void continuationAcceptsPendingPlanThenReturnsAuthoritativeClaim() {
        PlanningVersion version = pendingPlan();
        TrackedIssue claimed = issue();
        when(dispatch.claimReadyStart(10L)).thenReturn(IssueDispatchService.ClaimResult.claimed(claimed));
        assertThat(coordinator.continueAfterPlanning(issue)).isSameAs(claimed);
        var sequence = inOrder(plans, dispatch);
        sequence.verify(plans).approvePlan(10L, version.getId());
        sequence.verify(dispatch).claimReadyStart(10L);
    }

    @Test void rejectedClaimDoesNotProduceRunnableIssue() {
        pendingPlan();
        when(dispatch.claimReadyStart(10L)).thenReturn(IssueDispatchService.ClaimResult.rejected("paused"));
        assertThat(coordinator.continueAfterPlanning(issue)).isNull();
    }

    @Test void continuationOnlyRunsForManagedPlanningWait() {
        issue.setWorkflowPolicy(WorkflowPolicy.LEGACY);
        assertThat(coordinator.continueAfterPlanning(issue)).isNull();
        verifyNoInteractions(issues, plans, dispatch);
        issue.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        when(issues.findByIdWithApprovedPlanningVersion(10L)).thenReturn(Optional.of(issue));
        issue.setStatus(IssueStatus.FAILED);
        assertThat(coordinator.continueAfterPlanning(issue)).isNull();
        verifyNoInteractions(plans, dispatch);
    }

    private void approved(WorkflowStage stage) {
        StageApproval approval = new StageApproval();
        approval.setState(StageApproval.State.APPROVED);
        approval.setApprovedAt(LocalDateTime.now());
        approval.setProvider(AgentProvider.CODEX);
        approval.setModel("selected-model");
        when(stages.beforeStage(issue, stage, 1)).thenReturn(approval);
    }

    private PlanningVersion pendingPlan() {
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findByIdWithApprovedPlanningVersion(10L)).thenReturn(Optional.of(issue));
        PlanningVersion version = mock(PlanningVersion.class);
        when(version.getId()).thenReturn(20L);
        when(version.getState()).thenReturn(PlanningVersionState.PENDING);
        when(versions.findByIssueIdOrderByVersionNumberDesc(10L)).thenReturn(List.of(version));
        return version;
    }
}
