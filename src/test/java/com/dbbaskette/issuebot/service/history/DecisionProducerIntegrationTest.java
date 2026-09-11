package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.approval.*;
import com.dbbaskette.issuebot.service.workflow.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"issuebot.github.token=test-token", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@WithDecisionHistory
@Import({IssueOperatorTransactionService.class, ProcessingControlService.class, IterationManager.class,
        ApprovalDecisionService.class, ApprovalDecisionTransactionManager.class, ApprovalIntentRecovery.class,
        IssueDispatchTransactionManager.class, PlanFirstTransactionManager.class,
        StageApprovalService.class, DecompositionReservationService.class,
        DecompositionGroupTransactionManager.class, QueueRecoveryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DecisionProducerIntegrationTest {
    @Autowired IssueOperatorTransactionService operators;
    @Autowired ProcessingControlService control;
    @Autowired IterationManager iterationManager;
    @Autowired IssueDispatchTransactionManager dispatch;
    @Autowired ApprovalDecisionService approval;
    @Autowired ApprovalDecisionTransactionManager approvalTx;
    @Autowired ApprovalIntentRecovery recovery;
    @Autowired PlanFirstTransactionManager plans;
    @Autowired StageApprovalService stages;
    @Autowired DecompositionGroupTransactionManager groups;
    @Autowired DecisionHistoryService history;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired IssueGuidanceRepository guidance;
    @Autowired PlanningVersionRepository versions;
    @Autowired OperatorTransitionRepository intents;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GitHubApiClient github;
    @MockitoBean EventService events;
    @MockitoBean NotificationService notifications;
    @MockitoBean WorkflowCancellationService cancellation;
    @MockitoBean StageModelSelectionService selections;
    @MockitoBean com.dbbaskette.issuebot.config.IssueBotProperties properties;
    final ObjectMapper json = new ObjectMapper();
    TrackedIssue issue;

    @BeforeEach void seed() {
        jdbc.update("UPDATE processing_control SET state='RUNNING'");
        control.initialize();
        when(properties.getMaxConcurrentIssues()).thenReturn(5);
        when(selections.defaults(any(), any())).thenReturn(new com.dbbaskette.issuebot.service.harness.HarnessSelection(null, null, null));
        when(selections.resolve(any(), any(), any(), any(), any())).thenReturn(new com.dbbaskette.issuebot.service.harness.HarnessSelection(null, null, null));
        var repo = repos.saveAndFlush(new WatchedRepo("producer", "fixtures"));
        issue = issues.saveAndFlush(new TrackedIssue(repo, 1, "secret-title-do-not-copy"));
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM issue_decisions");
        jdbc.update("DELETE FROM operator_transitions");
        jdbc.update("DELETE FROM decomposition_children");
        jdbc.update("DELETE FROM decomposition_groups");
        jdbc.update("UPDATE tracked_issues SET approved_planning_version_id=NULL");
        jdbc.update("DELETE FROM planning_versions");
        jdbc.update("DELETE FROM iterations");
        jdbc.update("DELETE FROM stage_approvals");
        guidance.deleteAll(); issues.deleteAll(); repos.deleteAll();
    }
    void status(IssueStatus state) { issue.setStatus(state); issue = issues.saveAndFlush(issue); }
    List<IssueDecision> rows() { return history.page(issue.getId(), PageRequest.of(0, 25)).getContent(); }
    TransactionTemplate tx() { return new TransactionTemplate(manager); }

    @Test void guidanceTokenDeduplicatesConflictsAndAllowsLaterIdenticalGuidance() {
        status(IssueStatus.IN_PROGRESS);
        var first = operators.guide(issue.getId(), " raw-secret-guidance ", "request-one");
        var duplicate = operators.guide(issue.getId(), "raw-secret-guidance", "request-one");
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.guidance().getId()).isEqualTo(first.guidance().getId());
        assertThatThrownBy(() -> operators.guide(issue.getId(), "different-secret", "request-one"))
                .isInstanceOf(IllegalArgumentException.class);
        operators.guide(issue.getId(), "raw-secret-guidance", "request-two");
        assertThat(rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getAction()).isEqualTo(Action.GUIDE);
            assertThat(row.asDraft().toString()).doesNotContain("secret", "request-one", "request-two");
        });
    }
    @Test void guidanceRollbackAndInvalidTokenLeaveNoAcceptedRecord() {
        status(IssueStatus.IN_PROGRESS);
        tx().executeWithoutResult(t -> { operators.guide(issue.getId(), "secret", "rollback"); t.setRollbackOnly(); });
        assertThat(rows()).isEmpty(); assertThat(guidance.count()).isZero();
        assertThatThrownBy(() -> operators.guide(issue.getId(), "secret", "raw token=bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }
    @Test void guidanceCommentConfirmedAndUnknownOutcomesDoNotChangeAcceptance() {
        status(IssueStatus.IN_PROGRESS);
        var first = operators.guide(issue.getId(), "private guidance", "comment-one");
        operators.guidanceCommentResult(issue.getId(), first.guidance().getId(), true);
        operators.guidanceCommentResult(issue.getId(), first.guidance().getId(), true);
        var second = operators.guide(issue.getId(), "other private guidance", "comment-two");
        operators.guidanceCommentResult(issue.getId(), second.guidance().getId(), false);
        assertThat(rows()).hasSize(4).extracting(IssueDecision::getOutcome)
                .containsExactly(Outcome.UNKNOWN, Outcome.ACCEPTED, Outcome.SUCCEEDED, Outcome.ACCEPTED);
        assertThat(guidance.count()).isEqualTo(2);
    }
    @Test void stopIntentRollsBackAndCancellationOnlyFollowsCommitOnce() {
        status(IssueStatus.IN_PROGRESS);
        tx().executeWithoutResult(t -> {
            operators.stop(issue.getId()); verifyNoInteractions(cancellation); t.setRollbackOnly();
        });
        assertThat(rows()).isEmpty(); verifyNoInteractions(cancellation);
        operators.stop(issue.getId()); operators.stop(issue.getId());
        verify(cancellation).requestCancel(issue.getId());
        assertThat(rows()).hasSize(1).first().extracting(IssueDecision::getAction).isEqualTo(Action.STOP);
    }
    @Test void startsAndRetriesHaveCorrectActorAndLaterRunIdentity() {
        assertThat(dispatch.claimStart(issue.getId(), IssueDispatchTransactionManager.StartMutation.none()).claimed()).isTrue();
        assertThat(dispatch.claimStart(issue.getId()).claimed()).isFalse();
        assertThat(rows()).hasSize(1).first().extracting(IssueDecision::getActor).isEqualTo(Actor.OPERATOR);
        status(IssueStatus.FAILED);
        dispatch.claimRetry(issue.getId(), i -> null, IssueDispatchTransactionManager.RetryMutation.none());
        issue = issues.findById(issue.getId()).orElseThrow();
        int firstRun = issue.getWorkflowRun();
        status(IssueStatus.FAILED);
        dispatch.claimRetry(issue.getId(), i -> null, IssueDispatchTransactionManager.RetryMutation.none());
        assertThat(rows()).hasSize(3);
        assertThat(rows().getFirst().getWorkflowRun()).endsWith(":" + (firstRun + 1));
    }
    @Test void automaticDispatchAndIterationRetryHaveDurableSources() {
        dispatch.claimStart(issue.getId());
        issue = issues.findById(issue.getId()).orElseThrow();
        var first = iterationManager.claimImplementationIteration(issue, 1);
        tx().executeWithoutResult(t -> jdbc.update("UPDATE iterations SET completed_at=CURRENT_TIMESTAMP WHERE id=?", first.getId()));
        issue = issues.findById(issue.getId()).orElseThrow();
        var second = iterationManager.claimImplementationIteration(issue, 2);
        var repeated = iterationManager.claimImplementationIteration(issue, 2);
        assertThat(repeated.getId()).isEqualTo(second.getId());
        assertThat(rows()).hasSize(2).allSatisfy(row -> assertThat(row.getActor()).isEqualTo(Actor.AUTOMATION));
        assertThat(rows().getFirst().getIterationId()).isEqualTo(second.getId());
    }
    @Test void ordinaryRetryGuidanceIsAtomicAndNotInjectedAgainAtTheIterationBoundary() {
        status(IssueStatus.FAILED);
        tx().executeWithoutResult(t -> {
            dispatch.claimRetry(issue.getId(), i -> null, IssueDispatchTransactionManager.RetryMutation.none(), "private retry guidance");
            t.setRollbackOnly();
        });
        assertThat(guidance.count()).isZero(); assertThat(rows()).isEmpty();
        dispatch.claimRetry(issue.getId(), i -> null, IssueDispatchTransactionManager.RetryMutation.none(), "private retry guidance");
        assertThat(rows()).hasSize(2).allSatisfy(row -> assertThat(row.getGuidanceId()).isNotNull());
        assertThat(rows()).extracting(IssueDecision::getAction).containsExactly(Action.GUIDE, Action.RETRY);
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(issue.getId())).isEmpty();
        assertThat(guidance.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getGuidance()).isEqualTo("private retry guidance");
            assertThat(row.getConsumedAt()).isNotNull();
        });
    }
    @Test void globalActionsRecordOnlyAffectedIssuesAndRollbackTogether() {
        var completed = new TrackedIssue(issue.getRepo(), 2, "completed");
        completed.setStatus(IssueStatus.COMPLETED); issues.saveAndFlush(completed);
        control.pauseAfterCurrent(); control.pauseAfterCurrent(); control.restart();
        assertThat(rows()).extracting(IssueDecision::getAction).containsExactly(Action.RESUME, Action.PAUSE);
        assertThat(history.page(completed.getId(), PageRequest.of(0,25))).isEmpty();
        status(IssueStatus.IN_PROGRESS);
        tx().executeWithoutResult(t -> { control.stopNow(); t.setRollbackOnly(); });
        assertThat(rows()).hasSize(2); verifyNoInteractions(cancellation);
        control.stopNow(); assertThat(rows().getFirst().getAction()).isEqualTo(Action.STOP);
    }
    @Test void planApprovalAndRevisionAreBoundToTheExactVersion() {
        status(IssueStatus.AWAITING_PLAN_APPROVAL);
        var first = versions.saveAndFlush(PlanningVersion.pending(issue, 1, "secret-spec", "secret-plan", "CODEX", "test", null));
        plans.requestRevision(issue.getId(), first.getId(), "secret-revision");
        assertThatThrownBy(() -> plans.requestRevision(issue.getId(), first.getId(), "secret-revision"))
                .isInstanceOf(IllegalStateException.class);
        status(IssueStatus.AWAITING_PLAN_APPROVAL);
        var second = versions.saveAndFlush(PlanningVersion.pending(issue, 2, "secret", "secret", "CODEX", "test", null));
        plans.approvePlan(issue.getId(), second.getId());
        assertThat(rows()).extracting(IssueDecision::getAction).containsExactly(Action.APPROVE, Action.REJECT);
        assertThat(rows()).extracting(IssueDecision::getPlanVersionId).containsExactly(second.getId(), first.getId());
    }
    @Test void automaticPlanAcceptanceAndReadyStartAreNotAttributedToTheOperator() {
        status(IssueStatus.AWAITING_PLAN_APPROVAL);
        var version = versions.saveAndFlush(PlanningVersion.pending(issue, 1, "spec", "plan", "CODEX", "test", null));
        plans.approvePlan(issue.getId(), version.getId(), Actor.AUTOMATION);
        dispatch.claimReadyStart(issue.getId());
        assertThat(rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getActor()).isEqualTo(Actor.AUTOMATION);
            assertThat(row.getReason()).isEqualTo(Reason.POLICY_AUTOMATIC);
        });
    }
    @Test void prIntentIsCommittedBeforeExternalCallAndInFlightDuplicateCannotReleaseIt() throws Exception {
        status(IssueStatus.AWAITING_APPROVAL); issue.setPrNumber(55); issue = issues.saveAndFlush(issue);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(rows()).hasSize(1);
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return json.createObjectNode().put("merged", false).put("state", "open");
        });
        when(github.mergePullRequest(anyString(), anyString(), eq(55), anyString(), anyString()))
                .thenReturn(json.createObjectNode().put("merged", true));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> approval.approve(issue.getId(), true));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(approval.approve(issue.getId(), true).outcome()).isEqualTo(ApprovalDecisionService.Outcome.MERGE_OUTCOME_UNKNOWN);
            assertThat(approval.reject(issue.getId(), "secret-feedback").outcome()).isEqualTo(ApprovalDecisionService.Outcome.MERGE_OUTCOME_UNKNOWN);
            assertThat(intents.findAll().getFirst().getState()).isEqualTo(OperatorTransition.State.IN_FLIGHT);
            verify(github, times(1)).getPullRequest(anyString(), anyString(), anyInt());
            release.countDown(); assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ApprovalDecisionService.Outcome.APPROVED);
            assertThat(rows()).extracting(IssueDecision::getOutcome).containsExactly(Outcome.SUCCEEDED, Outcome.ACCEPTED);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void unknownMergeNeverReplaysAndRestartOnlyRecordsUncertainty() {
        status(IssueStatus.AWAITING_APPROVAL); issue.setPrNumber(55); issue = issues.saveAndFlush(issue);
        var claim = approvalTx.begin(issue.getId(), true);
        recovery.recover(); verifyNoInteractions(github);
        assertThat(intents.findById(claim.intent().getId()).orElseThrow().getState()).isEqualTo(OperatorTransition.State.UNKNOWN);
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenThrow(new IllegalStateException("secret-token timeout"));
        approval.approve(issue.getId(), true); approval.approve(issue.getId(), true);
        assertThat(rows()).hasSize(2).allSatisfy(row -> assertThat(row.asDraft().toString()).doesNotContain("secret", "timeout"));
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenReturn(json.createObjectNode().put("merged", true));
        assertThat(approval.approve(issue.getId(), true).outcome()).isEqualTo(ApprovalDecisionService.Outcome.APPROVED);
        verify(github, never()).mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString());
        assertThat(rows()).hasSize(3);
    }
    @Test void confirmedOpenAllowsANewExplicitIntentButUnknownDoesNot() {
        status(IssueStatus.AWAITING_APPROVAL); issue.setPrNumber(55); issue = issues.saveAndFlush(issue);
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenReturn(json.createObjectNode().put("state", "open"));
        when(github.mergePullRequest(anyString(), anyString(), eq(55), anyString(), anyString()))
                .thenThrow(new IllegalStateException("secret-conflict"));
        assertThat(approval.approve(issue.getId(), true).outcome()).isEqualTo(ApprovalDecisionService.Outcome.MERGE_CONFIRMED_OPEN);
        assertThat(approval.approve(issue.getId(), true).outcome()).isEqualTo(ApprovalDecisionService.Outcome.MERGE_CONFIRMED_OPEN);
        assertThat(intents.count()).isEqualTo(2); assertThat(rows()).hasSize(4);
    }
    @Test void failedLocalFinalizationPreservesIntentAndDoesNotRepeatConfirmedMerge() {
        status(IssueStatus.AWAITING_APPROVAL); issue.setPrNumber(55); issue = issues.saveAndFlush(issue);
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenReturn(json.createObjectNode().put("state", "open"));
        when(github.mergePullRequest(anyString(), anyString(), eq(55), anyString(), anyString()))
                .thenReturn(json.createObjectNode().put("merged", true));
        doThrow(new IllegalStateException("private persistence error")).when(events)
                .log(eq("APPROVAL_APPROVED"), anyString(), any(), any());
        assertThatThrownBy(() -> approval.approve(issue.getId(), true)).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).extracting(IssueDecision::getOutcome).containsExactly(Outcome.UNKNOWN, Outcome.ACCEPTED);
        assertThat(issues.findById(issue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        reset(events);
        when(github.getPullRequest(anyString(), anyString(), eq(55))).thenReturn(json.createObjectNode().put("merged", true));
        assertThat(approval.approve(issue.getId(), true).outcome()).isEqualTo(ApprovalDecisionService.Outcome.APPROVED);
        verify(github, times(1)).mergePullRequest(anyString(), anyString(), eq(55), anyString(), anyString());
    }
    @Test void decompositionAcceptanceAndOutcomeUseOneGroupIdentity() {
        issue.setDecompositionProposal("[]");
        status(IssueStatus.AWAITING_DECOMPOSITION);
        var group = groups.beginGroup(issue.getId(), List.of(), Actor.OPERATOR);
        assertThat(groups.beginGroup(issue.getId(), List.of(), Actor.OPERATOR).getId()).isEqualTo(group.getId());
        groups.recordError(group.getId(), "secret-exception"); groups.recordError(group.getId(), "different-secret");
        groups.activate(group.getId());
        assertThat(rows()).hasSize(3).allSatisfy(row -> assertThat(row.asDraft().toString()).doesNotContain("secret"));
    }
    @Test void stageReapprovalUsesANewGenerationAndFailedClaimRollsBackHistory() {
        issue.getRepo().setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        repos.saveAndFlush(issue.getRepo()); status(IssueStatus.IN_PROGRESS);
        var automatic = stages.beforeStage(issue, WorkflowStage.VERIFICATION, 1);
        stages.rearmAfterAuthenticationFailure(issue.getId(), automatic.getId());
        tx().executeWithoutResult(t -> {
            stages.approveAndClaim(issue.getId(), automatic.getId(), null, null, "operator");
            t.setRollbackOnly();
        });
        assertThat(rows()).hasSize(1);
        stages.approveAndClaim(issue.getId(), automatic.getId(), null, null, "operator");
        assertThatThrownBy(() -> stages.approveAndClaim(issue.getId(), automatic.getId(), null, null, "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows()).extracting(IssueDecision::getAction).containsExactly(Action.APPROVE, Action.AUTO_STAGE);
        assertThat(rows()).extracting(IssueDecision::getSourceKey).doesNotHaveDuplicates();
    }
    @Test void decompositionRejectionRollsBackThenRejectsDuplicateDelivery() {
        issue.setDecompositionProposal("secret proposal"); status(IssueStatus.AWAITING_DECOMPOSITION);
        tx().executeWithoutResult(t -> { groups.rejectProposal(issue.getId()); t.setRollbackOnly(); });
        assertThat(rows()).isEmpty();
        groups.rejectProposal(issue.getId());
        assertThatThrownBy(() -> groups.rejectProposal(issue.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).hasSize(1).first().extracting(IssueDecision::getAction).isEqualTo(Action.REJECT);
    }
}
