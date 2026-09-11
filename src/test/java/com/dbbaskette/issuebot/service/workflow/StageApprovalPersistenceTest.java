package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"issuebot.github.token=test-token", "spring.jpa.open-in-view=false"})
@Import({StageApprovalService.class, DecompositionReservationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StageApprovalPersistenceTest {
    @Autowired StageApprovalService service;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired StageApprovalRepository approvals;
    @MockitoBean StageModelSelectionService selection;
    @MockitoBean IssueBotProperties properties;
    @MockitoBean com.dbbaskette.issuebot.service.codex.ReasoningSelectionService reasoning;

    @Test void executionAuthenticationFailureDurablyRearmsSameApprovedReviewSelectionAndAttempt() {
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        when(selection.resolve(any(), any(), any(), any())).thenReturn(
                new StageModelSelectionService.Selection(IssueBotProperties.AgentProvider.CODEX, "gpt-6-astra"));
        when(reasoning.resolve(anyLong(), eq("gpt-6-astra"), eq(WorkflowStage.REVIEW))).thenReturn("high");
        when(reasoning.validate("gpt-6-astra", "ultra")).thenReturn("ultra");
        var repo = new WatchedRepo("stage", "expired-auth");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo = repos.saveAndFlush(repo);
        var issue = new TrackedIssue(repo, 2, "completed implementation awaits review");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(2);
        issue.setBranchName("issuebot/completed-implementation");
        issue.setClaudeSessionId("completed-session");
        issue = issues.saveAndFlush(issue);
        try {
            var decision = service.beforeStage(issue, WorkflowStage.REVIEW, 2);
            var claimed = service.approveAndClaim(issue.getId(), decision.getId(), "CODEX", "gpt-6-astra", "operator", "ultra");
            verify(selection).validate(any()); // Approval authentication succeeded.
            assertThat(claimed.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            var agent = mock(com.dbbaskette.issuebot.service.harness.CodingHarnessService.class);
            doThrow(new IllegalStateException("subscription expired")).doNothing()
                    .when(agent).pinSubscriptionHarness("codex");
            var coordinator = new StageWorkflowCoordinator(service, selection, agent, issues,
                    mock(PlanningVersionRepository.class), mock(PlanFirstTransactionManager.class), mock(IssueDispatchService.class));

            assertThat(coordinator.before(claimed, WorkflowStage.REVIEW, 2)).isFalse();

            var waiting = issues.findById(issue.getId()).orElseThrow();
            assertThat(waiting.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
            assertThat(waiting.getCurrentPhase()).isEqualTo("STAGE_APPROVAL_REVIEW");
            assertThat(waiting.getLastFailureReason()).contains("subscription");
            assertThat(issues.countByStatus(IssueStatus.IN_PROGRESS)).isZero();
            assertThat(service.pending(issue.getId())).hasValueSatisfying(saved -> {
                assertThat(saved.getId()).isEqualTo(decision.getId());
                assertThat(saved.getAttempt()).isEqualTo(2);
                assertThat(saved.getProvider()).isEqualTo(IssueBotProperties.AgentProvider.CODEX);
                assertThat(saved.getModel()).isEqualTo("gpt-6-astra");
                assertThat(saved.getReasoningEffort()).isEqualTo("ultra");
                assertThat(saved.getApprovedAt()).isNull();
            });

            var recovered = service.approveAndClaim(issue.getId(), decision.getId(), null, null, "operator");
            assertThat(recovered.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            assertThat(recovered.getCurrentIteration()).isEqualTo(2);
            assertThat(recovered.getBranchName()).isEqualTo("issuebot/completed-implementation");
            assertThat(recovered.getClaudeSessionId()).isEqualTo("completed-session");
            assertThat(coordinator.before(recovered, WorkflowStage.REVIEW, 2)).isTrue();
            assertThat(service.history(issue.getId())).singleElement().satisfies(saved -> {
                assertThat(saved.getId()).isEqualTo(decision.getId());
                assertThat(saved.getState()).isEqualTo(StageApproval.State.APPROVED);
                assertThat(saved.getAttempt()).isEqualTo(2);
                assertThat(saved.getReasoningEffort()).isEqualTo("ultra");
            });
        } finally {
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }

    @Test void simultaneousApprovalsCommitOnlyOneClaimAndPersistResumePhase() throws Exception {
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        when(selection.resolve(any(), any(), any(), any())).thenReturn(
                new StageModelSelectionService.Selection(null, null));
        WatchedRepo repo = new WatchedRepo("stage", "concurrency");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo = repos.saveAndFlush(repo);
        TrackedIssue issue = new TrackedIssue(repo, 1, "checkpoint");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue = issues.saveAndFlush(issue);
        StageApproval checkpoint = service.beforeStage(issue, WorkflowStage.REVIEW, 1);
        Long issueId = issue.getId();
        Long approvalId = checkpoint.getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            start.await();
            try {
                service.approveAndClaim(issueId, approvalId, null, null, "tester");
                return true;
            } catch (IllegalStateException rejected) {
                assertThat(rejected.getMessage()).contains("stale");
                return false;
            }
        };
        try {
            Future<Boolean> first = executor.submit(approve);
            Future<Boolean> second = executor.submit(approve);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            TrackedIssue resumed = issues.findById(issueId).orElseThrow();
            assertThat(resumed.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
            assertThat(resumed.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            assertThat(service.history(issueId)).singleElement().satisfies(decision -> {
                assertThat(decision.getState()).isEqualTo(StageApproval.State.APPROVED);
                assertThat(decision.getActor()).isEqualTo("tester");
            });
        } finally {
            executor.shutdownNow();
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }
}
