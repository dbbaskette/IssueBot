package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.harness.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageApprovalServiceTest {
    @Test void approvedLegacyBlankReasoningIsResolvedAndStoredBeforeResume() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        decision.setReasoningEffort(null);
        decision.setState(StageApproval.State.APPROVED);
        decision.setApprovedAt(java.time.LocalDateTime.now());
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(approvals.findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(2L, 0, WorkflowStage.REVIEW, 1, 0L))
                .thenReturn(Optional.of(decision));
        fixture.properties.getCodexCli().setReviewReasoningEffort("low");

        service.beforeStage(issue, WorkflowStage.REVIEW, 1);

        assertThat(decision.getReasoningEffort()).isEqualTo("medium");
        assertThat(decision.getModel()).isEqualTo("gpt-6-astra");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.APPROVED);
    }
    @Test void claudeStagePersistsAndRetainsItsExactReasoningTuple() {
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        selection = new StageModelSelectionService(properties, fixture.selections);
        service = new StageApprovalService(issues, repos, approvals, controls, reservations, selection, properties);
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        assertThat(decision.getHarnessId()).isEqualTo("claude");
        assertThat(decision.getReasoningEffort()).isEqualTo("high");
        service.approveAndClaim(2L, 3L, "claude", "claude-opus-4-8", "alice", "xhigh");
        assertThat(decision.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(decision.getReasoningEffort()).isEqualTo("xhigh");
    }

    @Test void unsupportedSelectionDoesNotMutateWaitingApprovalOrIssue() {
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        selection = new StageModelSelectionService(properties, fixture.selections);
        service = new StageApprovalService(issues, repos, approvals, controls, reservations, selection, properties);
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, "claude", "claude-haiku-4-5", "alice", "max"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max");
        assertThat(decision.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(decision.getReasoningEffort()).isEqualTo("high");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
    }
    TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
    StageApprovalRepository approvals = mock(StageApprovalRepository.class);
    ProcessingControlRepository controls = mock(ProcessingControlRepository.class);
    DecompositionReservationService reservations = mock(DecompositionReservationService.class);
    HarnessSelectionFixture fixture = new HarnessSelectionFixture();
    StageModelSelectionService selection = spy(new StageModelSelectionService(fixture.properties, fixture.selections));
    IssueBotProperties properties = new IssueBotProperties();
    StageApprovalService service = new StageApprovalService(issues, repos, approvals, controls,
            reservations, selection, properties);
    WatchedRepo repo = new WatchedRepo("owner", "repo");
    TrackedIssue issue = new TrackedIssue(repo, 1, "Issue");

    @BeforeEach void setup() {
        repo.setId(1L);
        issue.setId(2L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issues.findRepoIdByIssueId(2L)).thenReturn(Optional.of(1L));
        when(repos.findByIdForUpdate(1L)).thenReturn(Optional.of(repo));
        when(issues.findByIdForDispatch(2L)).thenReturn(Optional.of(issue));
        when(issues.findById(2L)).thenReturn(Optional.of(issue));
        when(issues.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(approvals.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        fixture.properties.setAgentProvider("codex");
        fixture.properties.getCodexCli().setReviewModel("gpt-6-astra");
        fixture.properties.getCodexCli().setReviewReasoningEffort("high");
        when(controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)).thenReturn(
                Optional.of(new ProcessingControl(ProcessingState.RUNNING)));
        when(reservations.evaluate(issue)).thenReturn(
                DecompositionReservationService.ReservationDecision.permitted());
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(eq(repo), any())).thenReturn(List.of(issue));
    }

    @Test void snapshotRemainsImmutableAndCopiesIntoDetachedCaller() {
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo.setApprovalStages("IMPLEMENTATION,MERGE");
        TrackedIssue caller = new TrackedIssue(repo, 1, "detached");
        caller.setId(2L);
        service.snapshot(caller);
        repo.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        service.snapshot(caller);
        assertThat(caller.getWorkflowPolicy()).isEqualTo(WorkflowPolicy.STAGED);
        assertThat(caller.getApprovalStages()).isEqualTo("IMPLEMENTATION,MERGE");
        caller.setPlanFirstOverride(false);
        assertThat(caller.effectivePlanFirst()).isTrue();
    }

    @Test void legacyDoesNotCreateStageRecord() {
        assertThat(service.beforeStage(issue, WorkflowStage.PLANNING, 1)).isNull();
        verifyNoInteractions(approvals, selection);
    }

    @Test void waitingGateIsIdempotentAndDoesNotRequireAuthUntilApproval() {
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        StageApproval result = service.beforeStage(issue, WorkflowStage.IMPLEMENTATION, 1);
        assertThat(result.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getCurrentPhase()).isEqualTo("STAGE_APPROVAL_IMPLEMENTATION");
        when(approvals.findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(2L, 0, WorkflowStage.IMPLEMENTATION, 1, 0L))
                .thenReturn(Optional.of(result));
        assertThat(service.beforeStage(issue, WorkflowStage.IMPLEMENTATION, 1)).isSameAs(result);
        verify(approvals, times(1)).saveAndFlush(any());
        verify(selection, never()).validate(any());
    }

    @Test void unselectedStageRecordsAutomaticProvenance() {
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo.setApprovalStages("MERGE");
        StageApproval result = service.beforeStage(issue, WorkflowStage.VERIFICATION, 2);
        assertThat(result.getState()).isEqualTo(StageApproval.State.APPROVED);
        assertThat(result.getActor()).isEqualTo("system");
        assertThat(result.getApprovedAt()).isNotNull();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(selection).validate(any());
    }

    @Test void manualIssueCanApproveWhileQueueRemainsPaused() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        issue.setManualDispatch(true);
        var control = new ProcessingControl(ProcessingState.PAUSE_AFTER_CURRENT);
        when(controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)).thenReturn(Optional.of(control));
        assertThat(service.approveAndClaim(2L, 3L, null, null, "alice").getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(control.getState()).isEqualTo(ProcessingState.PAUSE_AFTER_CURRENT);
        assertThat(decision.getState()).isEqualTo(StageApproval.State.APPROVED);
    }

    @Test void stageReasoningIsValidatedAndSaved() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        assertThat(decision.getReasoningEffort()).isEqualTo("high");
        assertThat(decision.getHarnessId()).isEqualTo("codex");
        service.approveAndClaim(2L, 3L, "CODEX", "gpt-6-astra", "alice", "ultra");
        assertThat(decision.getReasoningEffort()).isEqualTo("ultra");
        assertThat(decision.getHarnessId()).isEqualTo("codex");
    }

    @Test void existingApprovalRetainsNeutralIdentityModelAndReasoningWhenClaimed() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        decision.setHarnessId("CODEX");
        decision.setModel("gpt-6-astra");
        decision.setReasoningEffort("ultra");

        service.approveAndClaim(2L, 3L, null, null, "alice");

        assertThat(decision.getHarnessId()).isEqualTo("codex");
        assertThat(decision.getProvider()).isEqualTo(IssueBotProperties.AgentProvider.CODEX);
        assertThat(decision.getModel()).isEqualTo("gpt-6-astra");
        assertThat(decision.getReasoningEffort()).isEqualTo("ultra");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t "})
    void blankHarnessWritesCannotSelectDefaultProvider(String blank) {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        decision.setHarnessId(blank);
        assertThat(decision.getHarnessId()).isNull();
        assertThat(decision.getProvider()).isNull();
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("harness");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t "})
    void blankAuthoritativeIdentityCannotUseConflictingLegacyCodex(String blank) {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        ReflectionTestUtils.setField(decision, "harnessId", blank);
        clearInvocations(selection);
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("harness");
        assertThat(decision.getHarnessId()).isNull();
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        verifyNoInteractions(selection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t "})
    void blankLegacyIdentityCannotSelectDefaultProvider(String blank) {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        ReflectionTestUtils.setField(decision, "harnessId", null);
        ReflectionTestUtils.setField(decision, "provider", blank);
        clearInvocations(selection);
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("harness");
        assertThat(decision.getHarnessId()).isNull();
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        verifyNoInteractions(selection);
    }

    @Test void missingPersistedIdentityRequiresExplicitChoiceInsteadOfDefaultProvider() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        decision.setHarnessId(null);
        clearInvocations(selection);

        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("harness");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verifyNoInteractions(selection);
    }

    @Test void missingPersistedModelRequiresExplicitChoiceInsteadOfDefaultModel() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        decision.setModel(null);
        clearInvocations(selection);

        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("model");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        verifyNoInteractions(selection);
    }

    private StageApproval waiting(WorkflowStage stage) {
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        StageApproval decision = service.beforeStage(issue, stage, 1);
        decision.setId(3L);
        when(approvals.findById(3L)).thenReturn(Optional.of(decision));
        when(approvals.findFirstByIssueIdAndRunNumberAndStateOrderByIdDesc(2L, 0, StageApproval.State.WAITING))
                .thenReturn(Optional.of(decision));
        return decision;
    }

    @Test void approvalClaimsVerificationWithoutImplementationReplayAndRejectsDuplicate() {
        StageApproval decision = waiting(WorkflowStage.VERIFICATION);
        assertThat(service.approveAndClaim(2L, 3L, null, null, "alice")).isSameAs(issue);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issue.getCurrentPhase()).isEqualTo("LOCAL_CHECKS");
        assertThat(decision.getActor()).isEqualTo("alice");
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
    }

    @Test void pausedAndCapacityFailuresLeaveApprovalWaiting() {
        StageApproval decision = waiting(WorkflowStage.MERGE);
        when(controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)).thenReturn(
                Optional.of(new ProcessingControl(ProcessingState.STOPPED)));
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .hasMessageContaining("paused");
        when(controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)).thenReturn(
                Optional.of(new ProcessingControl(ProcessingState.RUNNING)));
        when(issues.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(3L);
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .hasMessageContaining("concurrency");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
    }

    @Test void authFailureDoesNotClaimIssue() {
        StageApproval decision = waiting(WorkflowStage.REVIEW);
        doThrow(new IllegalStateException("subscription authentication unavailable"))
                .when(selection).validate(any());
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .hasMessageContaining("authentication");
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
    }

    @Test void reusedAttemptInNewRunRequiresFreshDecision() {
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        StageApproval old = service.beforeStage(issue, WorkflowStage.IMPLEMENTATION, 1);
        old.setState(StageApproval.State.APPROVED);
        old.setApprovedAt(java.time.LocalDateTime.now());
        when(approvals.findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(
                2L, 0, WorkflowStage.IMPLEMENTATION, 1, 0L)).thenReturn(Optional.of(old));
        issue.setWorkflowRun(1);
        issue.setCurrentIteration(0);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        StageApproval fresh = service.beforeStage(issue, WorkflowStage.IMPLEMENTATION, 1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.getRunNumber()).isEqualTo(1);
        assertThat(fresh.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
    }

    @Test void oldRunApprovalCannotClaimCurrentWait() {
        StageApproval old = waiting(WorkflowStage.IMPLEMENTATION);
        issue.setWorkflowRun(1);
        assertThatThrownBy(() -> service.approveAndClaim(2L, 3L, null, null, "alice"))
                .hasMessageContaining("stale");
        assertThat(old.getState()).isEqualTo(StageApproval.State.WAITING);
    }

    @Test void unavailableAutomaticStageBecomesRecoverableWait() {
        repo.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        doThrow(new IllegalStateException("untrusted diagnostic"))
                .when(selection).validate(any());
        StageApproval decision = service.beforeStage(issue, WorkflowStage.REVIEW, 1);
        assertThat(decision.getState()).isEqualTo(StageApproval.State.WAITING);
        assertThat(issue.getCurrentPhase()).isEqualTo("STAGE_APPROVAL_REVIEW");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        assertThat(issue.getLastFailureReason()).contains("subscription").doesNotContain("untrusted");
    }
}
