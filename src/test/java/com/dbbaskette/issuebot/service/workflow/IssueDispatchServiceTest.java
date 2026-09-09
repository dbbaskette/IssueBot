package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IssueDispatchServiceTest {

    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final ProcessingControlService control = mock(ProcessingControlService.class);
    private final IterationRepository iterations = mock(IterationRepository.class);
    private IssueDispatchService service;
    private TrackedIssue issue;

    @BeforeEach
    void setUp() {
        when(control.isRunning()).thenReturn(true);
        issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test");
        issue.setId(1L);
        issue.setStatus(IssueStatus.PENDING);
        when(issues.findById(1L)).thenAnswer(invocation -> Optional.of(issue));
        when(issues.findByIdWithApprovedPlanningVersion(1L))
                .thenAnswer(invocation -> Optional.of(issue));
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of());
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());
        service = new IssueDispatchService(issues, control, iterations);
    }

    @Test
    void pausedClaimReturnsSpecificReasonWithoutSaving() {
        when(control.isRunning()).thenReturn(false);

        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).isEqualTo("Processing is paused");
        verify(issues, never()).save(any());
    }

    @Test
    void pendingIssueCanBeClaimed() {
        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isTrue();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(issues).save(issue);
    }

    @Test
    void activeRepoWorkReturnsSpecificReason() {
        TrackedIssue active = new TrackedIssue(issue.getRepo(), 41, "Active");
        active.setStatus(IssueStatus.IN_PROGRESS);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(active));

        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("#41", "currently running");
    }

    @Test
    void readyReservationOwnerIgnoresItselfAndCanStart() {
        PlanningVersion approved = approvedVersion(issue);
        issue.setApprovedPlanningVersion(approved);
        issue.setStatus(IssueStatus.READY_TO_START);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(issue));

        IssueDispatchService.ClaimResult result = service.claimReadyStart(1L);

        assertThat(result.claimed()).isTrue();
        assertThat(result.issue().getApprovedPlanningVersion()).isSameAs(approved);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    void genericClaimStartRejectsReadyReservation() {
        issue.setApprovedPlanningVersion(approvedVersion(issue));
        issue.setStatus(IssueStatus.READY_TO_START);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(issue));

        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Cannot start issue in READY_TO_START status");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        verify(issues, never()).save(any());
    }

    @Test
    void readyReservationUsesExactWordingForOtherStartAndRetry() {
        TrackedIssue reservation = new TrackedIssue(issue.getRepo(), 41, "Reserved");
        reservation.setId(41L);
        reservation.setStatus(IssueStatus.READY_TO_START);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(reservation));

        issue.setStatus(IssueStatus.QUEUED);
        IssueDispatchService.ClaimResult start = service.claimStart(1L);
        issue.setStatus(IssueStatus.FAILED);
        IssueDispatchService.ClaimResult retry = service.claimRetry(1L);

        assertThat(start.claimed()).isFalse();
        assertThat(start.reason())
                .isEqualTo("Issue #41 has an approved plan and is waiting to start.");
        assertThat(retry.claimed()).isFalse();
        assertThat(retry.reason())
                .isEqualTo("Issue #41 has an approved plan and is waiting to start.");
        verify(issues, never()).save(any());
    }

    @Test
    void legacyLowestReadyReservationOwnsDispatchRegardlessOfRepositoryQueryOrder() {
        assertLegacyLowestReadyReservationOwnsDispatch(false);
        assertLegacyLowestReadyReservationOwnsDispatch(true);
    }

    @Test
    void readyReservationUsesExactWordingForGuidedRetry() {
        issue.getRepo().setPlanFirst(true);
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        issue.setApprovedPlanningVersion(approvedVersion(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue))
                .thenReturn(List.of(review(issue, 2, false, "{}")));
        TrackedIssue reservation = new TrackedIssue(issue.getRepo(), 41, "Reserved");
        reservation.setId(41L);
        reservation.setStatus(IssueStatus.READY_TO_START);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(reservation));
        IssueGuidanceRepository guidance = mock(IssueGuidanceRepository.class);
        IssueDispatchService guidedService = new IssueDispatchService(
                issues, control, guidance, iterations);

        IssueDispatchService.ClaimResult result =
                guidedService.claimGuidedRetry(1L, "narrow fix", 5);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Issue #41 has an approved plan and is waiting to start.");
        verify(issues, never()).save(any());
        verifyNoInteractions(guidance);
    }

    @Test
    void legacyReleaseQueuesReadyReservationWhilePausedAndPreservesApprovedVersion() {
        PlanningVersion approved = approvedVersion(issue);
        issue.setApprovedPlanningVersion(approved);
        issue.setStatus(IssueStatus.READY_TO_START);
        issue.setPlanConformanceAttempt(2);
        issue.setCurrentPhase("WAITING");
        issue.setSuspensionReason("operator hold");
        when(control.isRunning()).thenReturn(false);

        IssueDispatchService.TransitionResult result = service.releaseReadyToQueue(1L);

        assertThat(result.transitioned()).isTrue();
        assertThat(result.issue()).isSameAs(issue);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(issue.getApprovedPlanningVersion()).isSameAs(approved);
        assertThat(issue.getPlanConformanceAttempt()).isEqualTo(2);
        assertThat(issue.getCurrentPhase()).isNull();
        assertThat(issue.getSuspensionReason()).isNull();
        verify(issues).save(issue);
        verify(control, never()).isRunning();
    }

    @Test
    void legacyReleaseRejectsStaleStateWithoutMutation() {
        issue.setStatus(IssueStatus.PENDING);

        IssueDispatchService.TransitionResult result = service.releaseReadyToQueue(1L);

        assertThat(result.transitioned()).isFalse();
        assertThat(result.reason())
                .isEqualTo("Issue is now PENDING; the repository slot was not changed");
        assertThat(result.issue()).isSameAs(issue);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.PENDING);
        verify(issues, never()).save(any());
    }

    @Test
    void guardedRetryChecksEligibilityOnFreshIssueBeforeSaving() {
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(1);

        IssueDispatchService.ClaimResult result = service.claimRetry(
                1L,
                candidate -> candidate.getPlanConformanceAttempt() == 2,
                "Guided retry requires a second conformance miss");

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("second conformance miss");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
        verify(issues, never()).save(any());
    }

    @Test
    void guardedRetryStillRespectsPauseAndRepositorySerialization() {
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(1);
        when(control.isRunning()).thenReturn(false);

        IssueDispatchService.ClaimResult paused = service.claimRetry(
                1L, candidate -> true, "not eligible");

        assertThat(paused.claimed()).isFalse();
        assertThat(paused.reason()).isEqualTo("Processing is paused");
        verify(issues, never()).save(any());

        reset(control);
        when(control.isRunning()).thenReturn(true);
        TrackedIssue active = new TrackedIssue(issue.getRepo(), 41, "Active");
        active.setStatus(IssueStatus.IN_PROGRESS);
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), any()))
                .thenReturn(List.of(active));

        IssueDispatchService.ClaimResult serialized = service.claimRetry(
                1L, candidate -> true, "not eligible");

        assertThat(serialized.claimed()).isFalse();
        assertThat(serialized.reason()).contains("#41", "currently running");
        verify(issues, never()).save(any());
    }

    @Test
    void legacyRetryRejectsLatestPersistedSecondMiss() {
        issue.getRepo().setPlanFirst(true);
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        when(iterations.findByIssueOrderByIterationNumAsc(issue))
                .thenReturn(List.of(review(issue, 2, false, "{}")));

        IssueDispatchService.ClaimResult result = service.claimRetry(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("guided implementation retry");
        verify(issues, never()).save(any());
    }

    @Test
    void legacyGuidedRetryAcceptsLatestPersistedSecondMiss() {
        issue.getRepo().setPlanFirst(true);
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = PlanningVersion.pending(
                issue, 1, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        issue.setApprovedPlanningVersion(approved);
        when(iterations.findByIssueOrderByIterationNumAsc(issue))
                .thenReturn(List.of(review(issue, 2, false, "{}")));
        IssueGuidanceRepository guidance = mock(IssueGuidanceRepository.class);
        IssueDispatchService guidedService = new IssueDispatchService(
                issues, control, guidance, iterations);

        IssueDispatchService.ClaimResult result =
                guidedService.claimGuidedRetry(1L, "narrow fix", 5);

        assertThat(result.claimed()).isTrue();
        verify(guidance).save(argThat(row -> row.getIssueId().equals(1L)
                && row.getGuidance().equals("narrow fix")));
    }

    @Test
    void legacyRetryAllowsLatestPersistedPassAfterEarlierMiss() {
        issue.getRepo().setPlanFirst(true);
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(
                review(issue, 1, false, "{}"), review(issue, 2, true, "{}")));

        IssueDispatchService.ClaimResult result = service.claimRetry(1L);

        assertThat(result.claimed()).isTrue();
        assertThat(issue.getWorkflowRun()).isEqualTo(1);
        verify(issues).save(issue);
    }

    @Test
    void legacyRetryTreatsNullPersistedVerdictAsNeutral() {
        issue.getRepo().setPlanFirst(true);
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        when(iterations.findByIssueOrderByIterationNumAsc(issue))
                .thenReturn(List.of(review(issue, 2, null, "{\"passed\":false}")));

        IssueDispatchService.ClaimResult result = service.claimRetry(1L);

        assertThat(result.claimed()).isTrue();
        verify(issues).save(issue);
    }

    @Test
    void concurrentGuardedRetriesCannotBothPassSharedCapacityGate() throws Exception {
        TrackedIssue firstIssue = new TrackedIssue(new WatchedRepo("acme", "one"), 41, "First");
        firstIssue.setId(1L);
        firstIssue.setStatus(IssueStatus.FAILED);
        TrackedIssue secondIssue = new TrackedIssue(new WatchedRepo("acme", "two"), 42, "Second");
        secondIssue.setId(2L);
        secondIssue.setStatus(IssueStatus.FAILED);
        when(issues.findById(1L)).thenReturn(Optional.of(firstIssue));
        when(issues.findById(2L)).thenReturn(Optional.of(secondIssue));
        when(issues.findByIdWithApprovedPlanningVersion(1L)).thenReturn(Optional.of(firstIssue));
        when(issues.findByIdWithApprovedPlanningVersion(2L)).thenReturn(Optional.of(secondIssue));

        AtomicInteger active = new AtomicInteger();
        doAnswer(invocation -> {
            active.incrementAndGet();
            return invocation.getArgument(0);
        }).when(issues).save(any());
        Function<TrackedIssue, String> capacityGate = candidate -> active.get() >= 1
                ? "Global concurrency limit reached" : null;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<IssueDispatchService.ClaimResult> firstClaim = () -> {
                ready.countDown();
                go.await();
                return service.claimRetry(1L, capacityGate);
            };
            Callable<IssueDispatchService.ClaimResult> secondClaim = () -> {
                ready.countDown();
                go.await();
                return service.claimRetry(2L, capacityGate);
            };
            var first = pool.submit(firstClaim);
            var second = pool.submit(secondClaim);
            ready.await();
            go.countDown();

            List<IssueDispatchService.ClaimResult> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(IssueDispatchService.ClaimResult::claimed)).hasSize(1);
            assertThat(results.stream().filter(result -> !result.claimed()).findFirst().orElseThrow().reason())
                    .contains("Global concurrency limit");
        }
    }

    @Test
    void concurrentClaimsDispatchOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<IssueDispatchService.ClaimResult> claim = () -> {
                ready.countDown();
                go.await();
                return service.claimStart(1L);
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            ready.await();
            go.countDown();

            assertThat(List.of(first.get(), second.get()).stream()
                    .filter(IssueDispatchService.ClaimResult::claimed)).hasSize(1);
        }
    }

    private static Iteration review(TrackedIssue issue, int number, Boolean passed, String json) {
        Iteration iteration = new Iteration(issue, number);
        iteration.setReviewPassed(passed);
        iteration.setReviewJson(json);
        return iteration;
    }

    private void assertLegacyLowestReadyReservationOwnsDispatch(boolean ownerFirst) {
        TrackedIssueRepository localIssues = mock(TrackedIssueRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "legacy-ready-order");
        TrackedIssue owner = new TrackedIssue(repo, 141, "Owner");
        owner.setId(141L);
        owner.setStatus(IssueStatus.READY_TO_START);
        owner.setApprovedPlanningVersion(approvedVersion(owner));
        TrackedIssue duplicate = new TrackedIssue(repo, 143, "Duplicate");
        duplicate.setId(143L);
        duplicate.setStatus(IssueStatus.READY_TO_START);
        duplicate.setApprovedPlanningVersion(approvedVersion(duplicate));
        List<TrackedIssue> active = ownerFirst
                ? List.of(owner, duplicate)
                : List.of(duplicate, owner);
        when(localIssues.findByIdWithApprovedPlanningVersion(141L)).thenReturn(Optional.of(owner));
        when(localIssues.findByIdWithApprovedPlanningVersion(143L)).thenReturn(Optional.of(duplicate));
        when(localIssues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(active);
        when(localIssues.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IssueDispatchService localService = new IssueDispatchService(
                localIssues, control, mock(IterationRepository.class));

        IssueDispatchService.ClaimResult duplicateResult = localService.claimReadyStart(143L);
        IssueDispatchService.ClaimResult ownerResult = localService.claimReadyStart(141L);

        assertThat(duplicateResult.claimed()).isFalse();
        assertThat(duplicateResult.reason())
                .isEqualTo("Issue #141 has an approved plan and is waiting to start.");
        assertThat(ownerResult.claimed()).isTrue();
    }

    private static PlanningVersion approvedVersion(TrackedIssue issue) {
        PlanningVersion approved = PlanningVersion.pending(
                issue, 1, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        return approved;
    }
}
