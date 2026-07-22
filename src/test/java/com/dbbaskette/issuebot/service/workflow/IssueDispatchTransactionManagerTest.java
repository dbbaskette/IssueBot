package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    @Autowired private IterationRepository iterations;
    @Autowired private ProcessingControlRepository controls;
    @MockitoSpyBean private IssueGuidanceRepository guidance;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreRunningProcessingState() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            ProcessingControl control = controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
            control.setState(ProcessingState.RUNNING);
            controls.saveAndFlush(control);
        });
    }

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
    void readyReservationOwnerCanStartWithApprovedVersion() {
        PendingVersion pending = seedPendingPlanningVersion();

        planTransactions.approvePlan(pending.issueId(), pending.versionId());

        TrackedIssue approved = issues.findByIdWithApprovedPlanningVersion(pending.issueId())
                .orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(approved.getApprovedPlanningVersion().getState())
                .isEqualTo(PlanningVersionState.APPROVED);

        IssueDispatchService.ClaimResult claim = dispatch.claimReadyStart(pending.issueId());
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
    void readyReservationWithoutApprovedVersionCannotStart() {
        Long issueId = seedIssue(IssueStatus.READY_TO_START, 44, null);

        IssueDispatchService.ClaimResult result = dispatch.claimReadyStart(issueId);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Ready-to-start issue has no approved planning version");
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
    }

    @Test
    void genericClaimStartCannotAutoStartReadyReservation() {
        Long issueId = seedApprovedIssue(IssueStatus.READY_TO_START, 0);

        IssueDispatchService.ClaimResult result = dispatch.claimStart(issueId);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Cannot start issue in READY_TO_START status");
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
    }

    @Test
    void readyReservationBlocksOtherStartAndRetry() {
        Long ownerId = seedApprovedIssue(IssueStatus.READY_TO_START, 0, 41);
        TrackedIssue owner = issues.findById(ownerId).orElseThrow();
        Long startId = seedIssue(owner.getRepo(), IssueStatus.QUEUED, 42);
        Long retryId = seedIssue(owner.getRepo(), IssueStatus.FAILED, 43);

        IssueDispatchService.ClaimResult start = dispatch.claimStart(startId);
        IssueDispatchService.ClaimResult retry = dispatch.claimRetry(
                retryId, issue -> null, IssueDispatchTransactionManager.RetryMutation.none());

        assertThat(start.claimed()).isFalse();
        assertThat(start.reason())
                .isEqualTo("Issue #41 has an approved plan and is waiting to start.");
        assertThat(retry.claimed()).isFalse();
        assertThat(retry.reason())
                .isEqualTo("Issue #41 has an approved plan and is waiting to start.");
        assertThat(issues.findById(startId).orElseThrow().getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(issues.findById(retryId).orElseThrow().getStatus()).isEqualTo(IssueStatus.FAILED);
    }

    @Test
    void lowestReadyReservationOwnsDispatchRegardlessOfRepositoryQueryOrder() {
        assertLowestReadyReservationOwnsDispatch(false);
        assertLowestReadyReservationOwnsDispatch(true);
    }

    @Test
    void dispatchLocksRepositoryBeforeTargetIssue() {
        TrackedIssueRepository mockIssues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository mockRepos = mock(WatchedRepoRepository.class);
        ProcessingControlRepository mockControls = mock(ProcessingControlRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "lock-order");
        repo.setId(9L);
        TrackedIssue candidate = new TrackedIssue(repo, 42, "Candidate");
        candidate.setId(42L);
        candidate.setStatus(IssueStatus.PENDING);
        when(mockControls.findByIdForUpdate(ProcessingControl.SINGLETON_ID))
                .thenReturn(Optional.of(new ProcessingControl(ProcessingState.RUNNING)));
        when(mockIssues.findRepoIdByIssueId(42L)).thenReturn(Optional.of(9L));
        when(mockRepos.findByIdForUpdate(9L)).thenReturn(Optional.of(repo));
        when(mockIssues.findByIdForDispatch(42L)).thenReturn(Optional.of(candidate));
        when(mockIssues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(List.of());
        when(mockIssues.saveAndFlush(candidate)).thenReturn(candidate);
        IssueDispatchTransactionManager manager = new IssueDispatchTransactionManager(
                mockIssues, mockRepos, mockControls, mock(IssueGuidanceRepository.class),
                mock(IterationRepository.class));

        IssueDispatchService.ClaimResult result = manager.claimStart(42L);

        assertThat(result.claimed()).isTrue();
        InOrder locking = inOrder(mockIssues, mockRepos);
        locking.verify(mockIssues).findRepoIdByIssueId(42L);
        locking.verify(mockRepos).findByIdForUpdate(9L);
        locking.verify(mockIssues).findByIdForDispatch(42L);
    }

    @Test
    void releaseReadyReservationQueuesIssueAndPreservesApprovedVersion() {
        Long issueId = seedApprovedIssue(IssueStatus.READY_TO_START, 2);
        TrackedIssue before = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        Long approvedVersionId = before.getApprovedPlanningVersion().getId();
        List<Long> historyIds = versions.findByIssueIdOrderByVersionNumberDesc(issueId).stream()
                .map(PlanningVersion::getId)
                .toList();

        IssueDispatchService.TransitionResult result = dispatch.releaseReadyToQueue(issueId);

        assertThat(result.transitioned()).isTrue();
        assertThat(result.issue().getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(result.issue().getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);
        TrackedIssue persisted = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(persisted.getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId))
                .extracting(PlanningVersion::getId)
                .containsExactlyElementsOf(historyIds);
        assertThat(persisted.getPlanConformanceAttempt()).isEqualTo(2);
    }

    @Test
    void releaseRejectsStaleStateWithoutMutation() {
        Long issueId = seedApprovedIssue(IssueStatus.PENDING, 1);
        Long approvedVersionId = issues.findByIdWithApprovedPlanningVersion(issueId)
                .orElseThrow().getApprovedPlanningVersion().getId();

        IssueDispatchService.TransitionResult result = dispatch.releaseReadyToQueue(issueId);

        assertThat(result.transitioned()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Issue is now PENDING; the repository slot was not changed");
        TrackedIssue persisted = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(persisted.getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);
    }

    @Test
    void concurrentStartAndReleaseHaveExactlyOneWinner() throws Exception {
        Long issueId = seedApprovedIssue(IssueStatus.READY_TO_START, 0);
        Long approvedVersionId = issues.findByIdWithApprovedPlanningVersion(issueId)
                .orElseThrow().getApprovedPlanningVersion().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = pool.submit(awaitThen(ready, go, () -> dispatch.claimReadyStart(issueId)));
            var release = pool.submit(awaitThen(ready, go, () -> dispatch.releaseReadyToQueue(issueId)));
            ready.await();
            go.countDown();

            IssueDispatchService.ClaimResult startResult = start.get();
            IssueDispatchService.TransitionResult releaseResult = release.get();
            assertThat(List.of(startResult.claimed(), releaseResult.transitioned()))
                    .containsExactlyInAnyOrder(true, false);
        }

        TrackedIssue persisted = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        assertThat(persisted.getStatus())
                .isIn(IssueStatus.IN_PROGRESS, IssueStatus.QUEUED);
        assertThat(persisted.getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);
    }

    @Test
    void completedReadyTransitionMakesCompetingCommandStaleInEitherOrder() {
        Long releaseFirstId = seedApprovedIssue(IssueStatus.READY_TO_START, 0);
        Long releaseFirstVersionId = issues.findByIdWithApprovedPlanningVersion(releaseFirstId)
                .orElseThrow().getApprovedPlanningVersion().getId();

        IssueDispatchService.TransitionResult released =
                dispatch.releaseReadyToQueue(releaseFirstId);
        IssueDispatchService.ClaimResult staleStart = dispatch.claimReadyStart(releaseFirstId);

        assertThat(released.transitioned()).isTrue();
        assertThat(staleStart.claimed()).isFalse();
        assertThat(staleStart.reason()).isEqualTo("Cannot start issue in QUEUED status");
        TrackedIssue releasedIssue = issues.findByIdWithApprovedPlanningVersion(releaseFirstId)
                .orElseThrow();
        assertThat(releasedIssue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(releasedIssue.getApprovedPlanningVersion().getId())
                .isEqualTo(releaseFirstVersionId);

        Long startFirstId = seedApprovedIssue(IssueStatus.READY_TO_START, 0);
        Long startFirstVersionId = issues.findByIdWithApprovedPlanningVersion(startFirstId)
                .orElseThrow().getApprovedPlanningVersion().getId();

        IssueDispatchService.ClaimResult started = dispatch.claimReadyStart(startFirstId);
        IssueDispatchService.TransitionResult staleRelease =
                dispatch.releaseReadyToQueue(startFirstId);

        assertThat(started.claimed()).isTrue();
        assertThat(staleRelease.transitioned()).isFalse();
        assertThat(staleRelease.reason())
                .isEqualTo("Issue is now IN_PROGRESS; the repository slot was not changed");
        TrackedIssue startedIssue = issues.findByIdWithApprovedPlanningVersion(startFirstId)
                .orElseThrow();
        assertThat(startedIssue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(startedIssue.getApprovedPlanningVersion().getId())
                .isEqualTo(startFirstVersionId);
    }

    @Test
    void concurrentOwnerStartAndCompetingStartCannotBothClaim() throws Exception {
        Long ownerId = seedApprovedIssue(IssueStatus.READY_TO_START, 0, 41);
        TrackedIssue owner = issues.findById(ownerId).orElseThrow();
        Long competingId = seedIssue(owner.getRepo(), IssueStatus.QUEUED, 42);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ownerStart = pool.submit(
                    awaitThen(ready, go, () -> dispatch.claimReadyStart(ownerId)));
            var competingStart = pool.submit(
                    awaitThen(ready, go, () -> dispatch.claimStart(competingId)));
            ready.await();
            go.countDown();

            IssueDispatchService.ClaimResult ownerResult = ownerStart.get();
            IssueDispatchService.ClaimResult competingResult = competingStart.get();
            assertThat(ownerResult.claimed()).isTrue();
            assertThat(competingResult.claimed()).isFalse();
            assertThat(competingResult.reason()).contains("Issue #41");
            assertThat(List.of(ownerResult, competingResult).stream()
                    .filter(IssueDispatchService.ClaimResult::claimed)).hasSize(1);
        }

        assertThat(issues.findById(ownerId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issues.findById(competingId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.QUEUED);
    }

    @Test
    void pausedReadyReservationCannotStartButCanReleaseSlot() {
        Long issueId = seedApprovedIssue(IssueStatus.READY_TO_START, 0);
        Long approvedVersionId = issues.findByIdWithApprovedPlanningVersion(issueId)
                .orElseThrow().getApprovedPlanningVersion().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            ProcessingControl control = controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseThrow();
            control.setState(ProcessingState.PAUSED);
            controls.saveAndFlush(control);
        });

        IssueDispatchService.ClaimResult start = dispatch.claimReadyStart(issueId);

        assertThat(start.claimed()).isFalse();
        assertThat(start.reason()).isEqualTo("Processing is paused");
        TrackedIssue reserved = issues.findByIdWithApprovedPlanningVersion(issueId).orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(reserved.getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);

        IssueDispatchService.TransitionResult release = dispatch.releaseReadyToQueue(issueId);

        assertThat(release.transitioned()).isTrue();
        assertThat(release.issue().getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(release.issue().getApprovedPlanningVersion().getId()).isEqualTo(approvedVersionId);
    }

    @Test
    void readyOwnerCannotStartWhenRepositoryIsUnexpectedlyOccupied() {
        Long ownerId = seedApprovedIssue(IssueStatus.READY_TO_START, 0, 41);
        TrackedIssue owner = issues.findById(ownerId).orElseThrow();
        seedIssue(owner.getRepo(), IssueStatus.IN_PROGRESS, 40);

        IssueDispatchService.ClaimResult result = dispatch.claimReadyStart(ownerId);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Issue #40 is currently running for this repository");
        assertThat(issues.findById(ownerId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
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
        seedReview(issueId, 2, false, "{}");

        IssueDispatchService.ClaimResult result = dispatch.claimRetry(issueId, issue -> null,
                IssueDispatchTransactionManager.RetryMutation.none());

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("guided implementation retry");
        assertThat(issues.findById(issueId).orElseThrow().getStatus()).isEqualTo(IssueStatus.FAILED);
    }

    @Test
    void genericRetryAllowsLatestPersistedPassAfterEarlierMiss() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);
        seedReview(issueId, 2, false, "{}");
        seedReview(issueId, 2, true, "{}");

        IssueDispatchService.ClaimResult result = dispatch.claimRetry(issueId, issue -> null,
                IssueDispatchTransactionManager.RetryMutation.none());

        assertThat(result.claimed()).isTrue();
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    void genericRetryTreatsNullPersistedVerdictAsNeutralEvenWhenJsonFails() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);
        seedReview(issueId, 2, null, "{\"passed\":false}");

        IssueDispatchService.ClaimResult result = dispatch.claimRetry(issueId, issue -> null,
                IssueDispatchTransactionManager.RetryMutation.none());

        assertThat(result.claimed()).isTrue();
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    void guidedRetryResetAndGuidanceInsertRollbackTogether() {
        Long issueId = seedApprovedIssue(IssueStatus.FAILED, 2);
        seedReview(issueId, 2, false, "{}");
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
        seedReview(issueId, 2, false, "{}");

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
        return seedApprovedIssue(status, conformanceAttempt, 42);
    }

    private void assertLowestReadyReservationOwnsDispatch(boolean ownerFirst) {
        TrackedIssueRepository mockIssues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository mockRepos = mock(WatchedRepoRepository.class);
        ProcessingControlRepository mockControls = mock(ProcessingControlRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "legacy-ready-order");
        repo.setId(9L);
        TrackedIssue owner = readyIssue(repo, 141L, 141);
        TrackedIssue duplicate = readyIssue(repo, 143L, 143);
        List<TrackedIssue> active = ownerFirst
                ? List.of(owner, duplicate)
                : List.of(duplicate, owner);
        when(mockControls.findByIdForUpdate(ProcessingControl.SINGLETON_ID))
                .thenReturn(Optional.of(new ProcessingControl(ProcessingState.RUNNING)));
        when(mockIssues.findRepoIdByIssueId(anyLong())).thenReturn(Optional.of(9L));
        when(mockRepos.findByIdForUpdate(9L)).thenReturn(Optional.of(repo));
        when(mockIssues.findByIdForDispatch(141L)).thenReturn(Optional.of(owner));
        when(mockIssues.findByIdForDispatch(143L)).thenReturn(Optional.of(duplicate));
        when(mockIssues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(active);
        when(mockIssues.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IssueDispatchTransactionManager manager = new IssueDispatchTransactionManager(
                mockIssues, mockRepos, mockControls, mock(IssueGuidanceRepository.class),
                mock(IterationRepository.class));

        IssueDispatchService.ClaimResult duplicateResult = manager.claimReadyStart(143L);
        IssueDispatchService.ClaimResult ownerResult = manager.claimReadyStart(141L);

        assertThat(duplicateResult.claimed()).isFalse();
        assertThat(duplicateResult.reason())
                .isEqualTo("Issue #141 has an approved plan and is waiting to start.");
        assertThat(ownerResult.claimed()).isTrue();
    }

    private static TrackedIssue readyIssue(WatchedRepo repo, Long id, int issueNumber) {
        TrackedIssue issue = new TrackedIssue(repo, issueNumber, "Ready " + issueNumber);
        issue.setId(id);
        issue.setStatus(IssueStatus.READY_TO_START);
        issue.setApprovedPlanningVersion(mock(PlanningVersion.class));
        return issue;
    }

    private Long seedApprovedIssue(IssueStatus status, int conformanceAttempt, int issueNumber) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
            WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets-seed" + System.nanoTime()));
            repo.setPlanFirst(true);
            TrackedIssue issue = new TrackedIssue(repo, issueNumber, "Approved work");
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

    private Long seedIssue(IssueStatus status, int issueNumber, WatchedRepo repo) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            controls.findById(ProcessingControl.SINGLETON_ID)
                    .orElseGet(() -> controls.save(new ProcessingControl(ProcessingState.RUNNING)));
            WatchedRepo owner = repo == null
                    ? repos.save(new WatchedRepo("acme", "unapproved-" + System.nanoTime()))
                    : repos.findById(repo.getId()).orElseThrow();
            TrackedIssue issue = new TrackedIssue(owner, issueNumber, "Unapproved work");
            issue.setStatus(status);
            return issues.saveAndFlush(issue).getId();
        });
    }

    private Long seedIssue(WatchedRepo repo, IssueStatus status, int issueNumber) {
        return seedIssue(status, issueNumber, repo);
    }

    private static <T> Callable<T> awaitThen(
            CountDownLatch ready, CountDownLatch go, Callable<T> command) {
        return () -> {
            ready.countDown();
            go.await();
            return command.call();
        };
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

    private void seedReview(Long issueId, int iterationNumber, Boolean passed, String reviewJson) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(ignored -> {
            TrackedIssue issue = issues.findById(issueId).orElseThrow();
            Iteration iteration = new Iteration(issue, iterationNumber);
            iteration.setReviewPassed(passed);
            iteration.setReviewJson(reviewJson);
            iterations.saveAndFlush(iteration);
        });
    }

    private record PendingVersion(Long issueId, Long versionId) { }
}
