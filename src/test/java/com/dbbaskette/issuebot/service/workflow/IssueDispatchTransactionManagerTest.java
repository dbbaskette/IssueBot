package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

@DataJpaTest
@Import({IssueDispatchTransactionManager.class, PlanFirstTransactionManager.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IssueDispatchTransactionManagerTest {

    @Autowired private IssueDispatchTransactionManager dispatch;
    @Autowired private PlanFirstTransactionManager planTransactions;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private PlanningVersionRepository versions;
    @Autowired private ProcessingControlRepository controls;
    @MockitoSpyBean private IssueGuidanceRepository guidance;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void approvedPendingIssueClaimsFreshInitializedEntityWithOsivOff() {
        Long issueId = seedApprovedIssue(IssueStatus.PENDING, 0);

        IssueDispatchService.ClaimResult result = dispatch.claimStart(issueId);

        assertThat(result.claimed()).isTrue();
        assertThat(result.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(result.issue().getRepo().fullName()).startsWith("acme/widgets-seed");
        assertThat(result.issue().getApprovedPlanningVersion().getState())
                .isEqualTo(PlanningVersionState.APPROVED);
        assertThat(result.issue().getApprovedPlanningVersion().getDesignSpec())
                .isEqualTo("approved spec");
    }

    @Test
    void realApprovalBecomesPendingThenDispatchesTheExactApprovedImplementationContext() {
        PendingVersion pending = seedPendingPlanningVersion();

        planTransactions.approvePlan(pending.issueId(), pending.versionId());

        TrackedIssue approved = issues.findByIdWithApprovedPlanningVersion(pending.issueId())
                .orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(approved.getApprovedPlanningVersion().getState())
                .isEqualTo(PlanningVersionState.APPROVED);

        IssueDispatchService.ClaimResult claim = dispatch.claimStart(pending.issueId());
        PlanFirstService planFirst = new PlanFirstService(
                mock(ClaudeCodeService.class), mock(GitHubApiClient.class), issues, versions,
                new PlanArtifactParser(), mock(PlanningWorkspaceService.class),
                mock(EventService.class), mock(NotificationService.class));
        ApprovedPlanContext context = planFirst.approvedContext(claim.issue()).orElseThrow();

        assertThat(claim.claimed()).isTrue();
        assertThat(claim.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(context.id()).isEqualTo(pending.versionId());
        assertThat(context.versionNumber()).isEqualTo(1);
        assertThat(context.designSpec()).isEqualTo("transactional design");
        assertThat(context.implementationPlan()).isEqualTo("transactional implementation");
    }

    @Test
    void awaitingPlanApprovalSerializesAnotherIssueInSameRepository() {
        controls.findById(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "serialized"));
        TrackedIssue waiting = new TrackedIssue(repo, 1, "Waiting");
        waiting.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issues.save(waiting);
        TrackedIssue candidate = new TrackedIssue(repo, 2, "Candidate");
        candidate.setStatus(IssueStatus.QUEUED);
        candidate = issues.save(candidate);

        IssueDispatchService.ClaimResult result = dispatch.claimStart(candidate.getId());

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("#1", "currently running");
        assertThat(issues.findById(candidate.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.QUEUED);
    }

    @Test
    void genericRetryRejectsSecondPlanFirstMiss() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);

        IssueDispatchService.ClaimResult result = dispatch.claimRetry(issueId, issue -> null,
                IssueDispatchTransactionManager.RetryMutation.none());

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("guided implementation retry");
        assertThat(issues.findById(issueId).orElseThrow().getStatus()).isEqualTo(IssueStatus.FAILED);
    }

    @Test
    void guidedRetryResetAndGuidanceInsertRollbackTogether() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);
        doThrow(new IllegalStateException("guidance insert fault"))
                .when(guidance).saveAndFlush(any(IssueGuidance.class));

        assertThatThrownBy(() -> dispatch.claimGuidedRetry(issueId, "narrow guidance", 10))
                .hasMessageContaining("guidance insert fault");

        reset(guidance);
        TrackedIssue persisted = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(IssueStatus.FAILED);
        assertThat(persisted.getPlanConformanceAttempt()).isEqualTo(2);
        assertThat(persisted.getCurrentIteration()).isEqualTo(0);
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(issueId)).isEmpty();
    }

    @Test
    void guidedRetryCommitsResetAndGuidanceAsOneClaim() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);

        IssueDispatchService.ClaimResult result =
                dispatch.claimGuidedRetry(issueId, "keep the public API", 10);

        assertThat(result.claimed()).isTrue();
        assertThat(result.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(result.issue().getPlanConformanceAttempt()).isZero();
        assertThat(result.issue().getApprovedPlanningVersion().getState())
                .isEqualTo(PlanningVersionState.APPROVED);
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(issueId))
                .extracting(IssueGuidance::getGuidance)
                .containsExactly("keep the public API");
    }

    private Long seedApprovedIssue(IssueStatus status, int conformanceAttempt) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
            WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets-seed" + System.nanoTime()));
            repo.setPlanFirst(true);
            TrackedIssue issue = new TrackedIssue(repo, 42, "Approved work");
            issue.setStatus(status);
            issue.setPlanConformanceAttempt(conformanceAttempt);
            issue = issues.save(issue);
            PlanningVersion approved = PlanningVersion.pending(issue, 1,
                    "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
            approved.approve(LocalDateTime.now());
            approved = versions.save(approved);
            issue.setApprovedPlanningVersion(approved);
            issue.setPlanApproved(true);
            return issues.saveAndFlush(issue).getId();
        });
    }

    private PendingVersion seedPendingPlanningVersion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
            WatchedRepo repo = repos.save(new WatchedRepo(
                    "acme", "approval-dispatch-" + System.nanoTime()));
            repo.setPlanFirst(true);
            TrackedIssue issue = new TrackedIssue(repo, 43, "Approve then dispatch");
            issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
            issue.setResolvedAgentProvider(
                    com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider.CODEX);
            issue.setResolvedImplModel("gpt-5.6-sol");
            issue = issues.saveAndFlush(issue);
            PlanningVersion version = versions.saveAndFlush(PlanningVersion.pending(
                    issue, 1, "transactional design", "transactional implementation",
                    "CODEX", "gpt-5.6-sol", null));
            return new PendingVersion(issue.getId(), version.getId());
        });
    }

    private record PendingVersion(Long issueId, Long versionId) { }
}
