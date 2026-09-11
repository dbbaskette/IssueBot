package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.harness.*;
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

@com.dbbaskette.issuebot.service.history.WithDecisionHistory
@DataJpaTest(properties = {"issuebot.github.token=test-token", "spring.jpa.open-in-view=false"})
@Import({StageApprovalService.class, DecompositionReservationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StageApprovalPersistenceTest {
    @Autowired StageApprovalService service;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired StageApprovalRepository approvals;
    @Autowired com.dbbaskette.issuebot.service.history.DecisionHistoryService decisions;
    @Autowired PlanningVersionRepository versions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean StageModelSelectionService selection;
    @MockitoBean IssueBotProperties properties;

    // Catalog refreshes at each validation boundary must release capacity without losing the decision.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,model", "1,reasoning", "1,catalog", "2,model", "2,reasoning", "2,catalog", "3,model", "3,reasoning", "3,catalog"})
    void catalogChangeRearmsExactApprovedReviewAndRecovers(int failingRead, String change) {
        var adapter = mock(CodingHarnessAdapter.class);
        when(adapter.id()).thenReturn("codex");
        when(adapter.displayName()).thenReturn("Codex CLI");
        when(adapter.probeCliAvailability()).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.dbbaskette.issuebot.service.harness.HarnessReadiness.READY;
        });
        when(adapter.probeSubscriptionAuthentication()).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.dbbaskette.issuebot.service.harness.HarnessReadiness.READY;
        });
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var refreshAt = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        var original = new HarnessModel("gpt-6-astra", "Astra", "", "high", List.of("high", "ultra"));
        when(adapter.models()).thenAnswer(call -> {
            if (reads.incrementAndGet() < refreshAt.get()) return List.of(original);
            if (change.equals("catalog")) throw new IllegalStateException("secret-token from catalog command");
            return change.equals("model") ? List.of()
                    : List.of(new HarnessModel("gpt-6-astra", "Astra", "", "high", List.of("high")));
        });
        var config = new IssueBotProperties();
        config.setAgentProvider("codex");
        config.getCodexCli().setReviewModel("gpt-6-astra");
        config.getCodexCli().setReviewReasoningEffort("ultra");
        var realSelections = new StageModelSelectionService(config,
                new HarnessSelectionService(new CodingHarnessRegistry(List.of(adapter)), config, issues, approvals));
        when(selection.defaults(any(), any())).thenAnswer(call -> realSelections.defaults(call.getArgument(0), call.getArgument(1)));
        when(selection.resolve(any(), any(), any(), any(), any())).thenAnswer(call -> realSelections.resolve(
                call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        doAnswer(call -> { realSelections.validate(call.getArgument(0)); return null; }).when(selection).validate(any());
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        var repo = new WatchedRepo("stage", "catalog-change");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo = repos.saveAndFlush(repo);
        var issue = new TrackedIssue(repo, 1, "completed implementation");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setWorkflowRun(4);
        issue.setCurrentIteration(2);
        issue.setBranchName("issuebot/completed");
        issue.setClaudeSessionId("retained-session");
        issue = issues.saveAndFlush(issue);
        var plan = PlanningVersion.pending(issue, 1, "spec", "plan", "codex", "gpt-6-astra", null);
        plan.approve(java.time.LocalDateTime.now());
        plan = versions.saveAndFlush(plan);
        issue.setApprovedPlanningVersion(plan);
        issue = issues.saveAndFlush(issue);
        try {
            var decision = service.beforeStage(issue, WorkflowStage.REVIEW, 2);
            var claimed = service.approveAndClaim(issue.getId(), decision.getId(), null, null, "operator");
            var agent = mock(CodingHarnessService.class);
            var coordinator = new StageWorkflowCoordinator(service, selection, agent, issues,
                    mock(PlanningVersionRepository.class), mock(PlanFirstTransactionManager.class), mock(IssueDispatchService.class));
            reads.set(0);
            refreshAt.set(failingRead);

            assertThatCode(() -> assertThat(coordinator.before(claimed, WorkflowStage.REVIEW, 2)).isFalse())
                    .doesNotThrowAnyException();

            var waiting = issues.findById(issue.getId()).orElseThrow();
            assertThat(waiting.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
            assertThat(waiting.getCurrentPhase()).isEqualTo("STAGE_APPROVAL_REVIEW");
            assertThat(waiting.getLastFailureReason().toLowerCase(java.util.Locale.ROOT))
                    .contains(change, "choose").doesNotContain("secret");
            assertThat(issues.countByStatus(IssueStatus.IN_PROGRESS)).isZero();
            assertThat(service.pending(issue.getId())).hasValueSatisfying(saved -> {
                assertThat(saved.getId()).isEqualTo(decision.getId());
                assertThat(saved.getRunNumber()).isEqualTo(4);
                assertThat(saved.getAttempt()).isEqualTo(2);
                assertThat(saved.getStage()).isEqualTo(WorkflowStage.REVIEW);
                assertThat(saved.getArtifactVersionId()).isEqualTo(decision.getArtifactVersionId());
                assertThat(saved.getArtifactVersionId()).isPositive();
                assertThat(saved.getHarnessId()).isEqualTo("codex");
                assertThat(saved.getModel()).isEqualTo("gpt-6-astra");
                assertThat(saved.getReasoningEffort()).isEqualTo("ultra");
                assertThat(saved.getApprovedAt()).isNull();
            });
            verifyNoInteractions(agent);

            refreshAt.set(Integer.MAX_VALUE);
            var recovered = service.approveAndClaim(issue.getId(), decision.getId(), null, null, "operator");
            assertThat(recovered.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            assertThat(recovered.getCurrentIteration()).isEqualTo(2);
            assertThat(recovered.getBranchName()).isEqualTo("issuebot/completed");
            assertThat(recovered.getClaudeSessionId()).isEqualTo("retained-session");
            assertThat(coordinator.before(recovered, WorkflowStage.REVIEW, 2)).isTrue();
            assertThat(service.history(issue.getId())).singleElement().satisfies(saved -> {
                assertThat(saved.getId()).isEqualTo(decision.getId());
                assertThat(saved.getState()).isEqualTo(StageApproval.State.APPROVED);
                assertThat(saved.getReasoningEffort()).isEqualTo("ultra");
                assertThat(saved.getDecisionGeneration()).isEqualTo(2);
            });
            assertThat(decisions.page(issue.getId(), org.springframework.data.domain.PageRequest.of(0, 25)))
                    .hasSize(2);
        } finally {
            approvals.deleteAll();
            var saved = issues.findById(issue.getId()).orElseThrow();
            saved.setApprovedPlanningVersion(null);
            issues.saveAndFlush(saved);
            versions.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }

    @Test void physicallyBlankIdentityColumnsDoNotChooseClaudeOrFallBackToLegacyCodex() {
        var repo = repos.saveAndFlush(new WatchedRepo("stage", "blank-identities"));
        var issue = issues.saveAndFlush(new TrackedIssue(repo, 1, "blank approval identity"));
        try {
            jdbc.update("""
                    INSERT INTO stage_approvals (issue_id, stage, attempt, state, model)
                    VALUES (?, 'REVIEW', 1, 'WAITING', 'saved-model')
                    """, issue.getId());
            for (String[] identities : new String[][] {
                    {"", "CODEX"}, {" \t ", "CODEX"}, {null, ""}, {null, " \t "}}) {
                jdbc.update("UPDATE stage_approvals SET harness_id = ?, provider = ? WHERE issue_id = ?",
                        identities[0], identities[1], issue.getId());
                jdbc.update("UPDATE tracked_issues SET resolved_harness_id = ?, resolved_agent_provider = ? WHERE id = ?",
                        identities[0], identities[1], issue.getId());
                assertThat(service.history(issue.getId())).singleElement().satisfies(saved -> {
                    assertThat(saved.getHarnessId()).isNull();
                    assertThat(saved.getProvider()).isNull();
                    assertThat(saved.getState()).isEqualTo(StageApproval.State.WAITING);
                    assertThat(saved.getModel()).isEqualTo("saved-model");
                });
                var savedIssue = issues.findById(issue.getId()).orElseThrow();
                assertThat(savedIssue.getResolvedHarnessId()).isNull();
                assertThat(savedIssue.getResolvedAgentProvider()).isNull();
            }
        } finally {
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }

    @Test void legacyOnlyAndUnknownStageRowsHydrateWithoutChangingIdentity() {
        var repo = repos.saveAndFlush(new WatchedRepo("stage", "legacy-identities"));
        var issue = issues.saveAndFlush(new TrackedIssue(repo, 1, "legacy approval"));
        try {
            jdbc.update("""
                    INSERT INTO stage_approvals (issue_id, stage, attempt, state, provider, harness_id, model)
                    VALUES (?, 'REVIEW', 1, 'WAITING', 'CLAUDE_CODE', NULL, 'saved-model')
                    """, issue.getId());
            assertThat(service.history(issue.getId())).singleElement().satisfies(saved -> {
                assertThat(saved.getHarnessId()).isEqualTo("claude");
                assertThat(saved.getProvider()).isEqualTo(IssueBotProperties.AgentProvider.CLAUDE_CODE);
            });
            jdbc.update("UPDATE stage_approvals SET provider = 'OTHER', harness_id = 'other' WHERE issue_id = ?",
                    issue.getId());
            assertThat(service.history(issue.getId())).singleElement().satisfies(saved -> {
                assertThat(saved.getHarnessId()).isEqualTo("other");
                assertThat(saved.getProvider()).isNull();
                assertThat(saved.getModel()).isEqualTo("saved-model");
            });
        } finally {
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }

    @Test void executionAuthenticationFailureDurablyRearmsSameApprovedReviewSelectionAndAttempt() {
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        when(selection.defaults(any(), any())).thenReturn(new com.dbbaskette.issuebot.service.harness.HarnessSelection("codex", "gpt-6-astra", "high"));
        when(selection.resolve(any(), any(), any(), any(), any())).thenAnswer(call ->
                new com.dbbaskette.issuebot.service.harness.HarnessSelection("codex", "gpt-6-astra", call.getArgument(4)));
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
                assertThat(saved.getHarnessId()).isEqualTo("codex");
                assertThat(saved.getRunNumber()).isEqualTo(decision.getRunNumber());
                assertThat(saved.getArtifactVersionId()).isEqualTo(decision.getArtifactVersionId());
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
            assertThat(jdbc.queryForObject("SELECT harness_id FROM stage_approvals WHERE id = ?",
                    String.class, decision.getId())).isEqualTo("codex");
            assertThat(jdbc.queryForObject("SELECT provider FROM stage_approvals WHERE id = ?",
                    String.class, decision.getId())).isEqualTo("CODEX");
        } finally {
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }

    @Test void simultaneousApprovalsCommitOnlyOneClaimAndPersistResumePhase() throws Exception {
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        when(selection.defaults(any(), any())).thenReturn(new com.dbbaskette.issuebot.service.harness.HarnessSelection("codex", "gpt-6-astra", "high"));
        when(selection.resolve(any(), any(), any(), any(), any())).thenAnswer(call ->
                new com.dbbaskette.issuebot.service.harness.HarnessSelection("codex", "gpt-6-astra", call.getArgument(4)));
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
