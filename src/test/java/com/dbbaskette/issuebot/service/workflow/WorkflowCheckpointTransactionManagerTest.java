package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@Import({WorkflowCheckpointTransactionManager.class, PlanFirstTransactionManager.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowCheckpointTransactionManagerTest {

    @Autowired private WorkflowCheckpointTransactionManager checkpoints;
    @Autowired private PlanFirstTransactionManager planTransactions;
    @MockitoSpyBean private WatchedRepoRepository repos;
    @MockitoSpyBean private TrackedIssueRepository issues;
    @Autowired private PlanningVersionRepository versions;
    @MockitoSpyBean private IterationRepository iterations;
    @MockitoSpyBean private IssueGuidanceRepository guidance;
    @Autowired private EntityManager entityManager;

    @AfterEach
    void restoreLockingSpies() {
        reset(issues, repos);
    }

    @Test
    void guidanceIsConsumedOnlyWithDurableExactImplementationContext() {
        Baseline baseline = seed();
        guidance.save(new IssueGuidance(baseline.issueId(), "preserve null semantics"));
        guidance.save(new IssueGuidance(baseline.issueId(), "add a regression test"));

        WorkflowCheckpointTransactionManager.ImplementationContext prepared =
                checkpoints.prepareImplementationContext(
                        baseline.issueId(), baseline.iterationId(), "REVIEW FINDING: null branch");

        assertThat(prepared.text()).contains(
                "REVIEW FINDING: null branch",
                "preserve null semantics", "add a regression test");
        assertThat(prepared.guidanceApplied()).isTrue();
        Iteration persisted = iterations.findById(baseline.iterationId()).orElseThrow();
        assertThat(persisted.getImplementationContext()).isEqualTo(prepared.text());
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(baseline.issueId()))
                .isEmpty();

        // A restart reuses the byte-for-byte context; newly queued guidance waits for the next
        // implementation rather than changing a prompt whose durable checkpoint already exists.
        guidance.save(new IssueGuidance(baseline.issueId(), "arrived after prompt checkpoint"));
        WorkflowCheckpointTransactionManager.ImplementationContext resumed =
                checkpoints.prepareImplementationContext(
                        baseline.issueId(), baseline.iterationId(), "different in-memory value");
        assertThat(resumed.text()).isEqualTo(prepared.text());
        assertThat(resumed.guidanceApplied()).isFalse();
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(baseline.issueId()))
                .extracting(IssueGuidance::getGuidance)
                .containsExactly("arrived after prompt checkpoint");
    }

    @Test
    void contextPersistenceFaultLeavesGuidanceUnconsumed() {
        Baseline baseline = seed();
        guidance.save(new IssueGuidance(baseline.issueId(), "must survive crash"));
        doThrow(new IllegalStateException("context checkpoint fault"))
                .when(iterations).saveAndFlush(any(Iteration.class));

        assertThatThrownBy(() -> checkpoints.prepareImplementationContext(
                baseline.issueId(), baseline.iterationId(), null))
                .hasMessageContaining("context checkpoint fault");

        reset(iterations);
        assertThat(iterations.findById(baseline.iterationId()).orElseThrow()
                .getImplementationContext()).isNull();
        assertThat(guidance.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(baseline.issueId()))
                .extracting(IssueGuidance::getGuidance)
                .containsExactly("must survive crash");
    }

    @Test
    void successfulImplementationResultAndNextPhaseCommitBeforeEffects() {
        Baseline baseline = seed();
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("implementation completed");
        result.setSessionId("session-42");

        WorkflowCheckpointTransactionManager.ImplementationCheckpoint checkpoint =
                checkpoints.persistImplementationComplete(
                        baseline.issueId(), baseline.iterationId(), result, "+ durable diff");

        assertThat(checkpoint.issue().getCurrentPhase()).isEqualTo("LOCAL_CHECKS");
        assertThat(checkpoint.issue().getClaudeSessionId()).isEqualTo("session-42");
        assertThat(checkpoint.iteration().getImplementationCompletedAt()).isNotNull();
        assertThat(checkpoint.iteration().getImplementationSucceeded()).isTrue();
        assertThat(checkpoint.iteration().getClaudeOutput()).isEqualTo("implementation completed");
        assertThat(checkpoint.iteration().getDiff()).isEqualTo("+ durable diff");
    }

    @Test
    void implementationCheckpointLocksRepositoryBeforeJoinedIssueQuery() {
        Baseline baseline = seed();
        Long repoId = issues.findRepoIdByIssueId(baseline.issueId()).orElseThrow();
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("implementation completed");
        clearInvocations(issues, repos);

        checkpoints.persistImplementationComplete(
                baseline.issueId(), baseline.iterationId(), result, "+ durable diff");

        org.mockito.InOrder locking = inOrder(issues, repos);
        locking.verify(issues).findRepoIdByIssueId(baseline.issueId());
        locking.verify(repos).findByIdForUpdate(repoId);
        locking.verify(issues).findByIdForDispatch(baseline.issueId());
    }

    @Test
    void concurrentApprovalAndImplementationCheckpointSerializeWithoutDeadlock()
            throws Exception {
        CheckpointRaceSeed seed = seedCheckpointRace();
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("implementation completed during approval race");

        CheckpointRace race = raceApprovalAgainstCheckpoint(seed, () ->
                checkpoints.persistImplementationComplete(
                        seed.implementationIssueId(), seed.iterationId(), result, "+ durable diff"));

        assertThat(race.approval().value()).isNull();
        assertThat(race.approval().failure())
                .hasMessage("Issue #42 is already running later work in this repository. "
                        + "Finish or stop it before approving issue #41.");
        assertThat(race.checkpoint().failure()).isNull();
        assertThat(race.checkpoint().value()).isNotNull();
        assertNoSerializationFailure(race.approval().failure());
        assertThat(issues.findById(seed.ownerIssueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issues.findById(seed.implementationIssueId()).orElseThrow().getCurrentPhase())
                .isEqualTo("LOCAL_CHECKS");
        assertThat(iterations.findById(seed.iterationId()).orElseThrow()
                .getImplementationSucceeded()).isTrue();
    }

    @Test
    void checkpointCompatibilityConstructorFailsClosedBeforeAnyMutationAccess() {
        TrackedIssueRepository mockIssues = mock(TrackedIssueRepository.class);
        IterationRepository mockIterations = mock(IterationRepository.class);
        IssueGuidanceRepository mockGuidance = mock(IssueGuidanceRepository.class);
        WorkflowCheckpointTransactionManager unlocked =
                new WorkflowCheckpointTransactionManager(
                        mockIssues, mockIterations, mockGuidance);

        assertThatThrownBy(() -> unlocked.cancelForOperator(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository locking is required for workflow checkpoint mutations");

        verifyNoInteractions(mockIssues, mockIterations, mockGuidance);
    }

    @Test
    void implementationCheckpointFaultRollsBackResultAndPhase() {
        Baseline baseline = seed();
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("must roll back");
        doThrow(new IllegalStateException("implementation checkpoint fault"))
                .when(iterations).saveAndFlush(any(Iteration.class));

        assertThatThrownBy(() -> checkpoints.persistImplementationComplete(
                baseline.issueId(), baseline.iterationId(), result, "+diff"))
                .hasMessageContaining("implementation checkpoint fault");

        reset(iterations);
        assertThat(issues.findById(baseline.issueId()).orElseThrow().getCurrentPhase())
                .isEqualTo("IMPLEMENTATION");
        Iteration persisted = iterations.findById(baseline.iterationId()).orElseThrow();
        assertThat(persisted.getImplementationCompletedAt()).isNull();
        assertThat(persisted.getClaudeOutput()).isNull();
        assertThat(persisted.getDiff()).isNull();
    }

    @Test
    void globalPauseDuringClaimedCorrectionRearmsTheExactIteration() {
        Baseline baseline = seed();
        TrackedIssue issue = issues.findById(baseline.issueId()).orElseThrow();
        issue.setCurrentIteration(2);
        issue.setPlanConformanceAttempt(1);
        issue.setPlanCorrectionPending(false);
        issues.saveAndFlush(issue);
        Iteration correction = new Iteration(issue, 2);
        correction.setImplementationContext("durable correction guidance");
        correction.setImplementationContextPrepared(true);
        correction = iterations.saveAndFlush(correction);

        TrackedIssue suspended = checkpoints.suspendForRecovery(issue.getId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(suspended.getCurrentIteration()).isEqualTo(1);
        assertThat(suspended.isPlanCorrectionPending()).isTrue();
        assertThat(suspended.getCurrentPhase()).isNull();
        assertThat(iterations.findById(correction.getId()).orElseThrow()
                .getImplementationContext()).isEqualTo("durable correction guidance");
    }

    @Test
    void globalPauseAfterImplementationPreservesExactResumeCheckpoint() {
        Baseline baseline = seed();
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("done");
        checkpoints.persistImplementationComplete(
                baseline.issueId(), baseline.iterationId(), result, "+diff");

        TrackedIssue suspended = checkpoints.suspendForRecovery(baseline.issueId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(suspended.getCurrentPhase()).isEqualTo("LOCAL_CHECKS");
        assertThat(suspended.getCurrentIteration()).isEqualTo(1);
    }

    @Test
    void globalPauseDuringReviewRetryPreservesReviewCheckpoint() {
        Baseline baseline = seed();
        TrackedIssue issue = issues.findById(baseline.issueId()).orElseThrow();
        issue.setCurrentPhase("INDEPENDENT_REVIEW");
        issues.saveAndFlush(issue);

        TrackedIssue suspended = checkpoints.suspendForRecovery(issue.getId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(suspended.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
        assertThat(suspended.getCurrentIteration()).isEqualTo(1);
    }

    @Test
    void globalPauseDuringPlanningRearmsPlanningWithoutFailure() {
        Baseline baseline = seed();
        TrackedIssue issue = issues.findById(baseline.issueId()).orElseThrow();
        issue.setCurrentIteration(0);
        issue.setCurrentPhase("PLANNING");
        issue.setLastFailureReason("provider exited after cancellation");
        issues.saveAndFlush(issue);

        TrackedIssue suspended = checkpoints.suspendForRecovery(issue.getId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(suspended.getCurrentPhase()).isNull();
        assertThat(suspended.getLastFailureReason()).isNull();
        assertThat(suspended.getSuspensionReason()).contains("paused");
    }

    @Test
    void globalPauseRacingAfterProposalCommitPreservesThePlanApprovalGate() {
        Baseline baseline = seed();
        TrackedIssue issue = issues.findById(baseline.issueId()).orElseThrow();
        issue.setCurrentIteration(0);
        issue.setCurrentPhase(null);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issues.saveAndFlush(issue);

        TrackedIssue suspended = checkpoints.suspendForRecovery(issue.getId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(suspended.getCurrentPhase()).isNull();
        assertThat(suspended.getLastFailureReason()).isNull();
    }

    @Test
    void globalPauseRacingAfterApprovalPreservesReadyReservation() {
        Baseline baseline = seed();
        TrackedIssue issue = issues.findById(baseline.issueId()).orElseThrow();
        PlanningVersion approved = PlanningVersion.pending(
                issue, 1, "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        approved = versions.saveAndFlush(approved);
        issue.setApprovedPlanningVersion(approved);
        issue.setCurrentIteration(2);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue.setPlanConformanceAttempt(1);
        issue.setPlanCorrectionPending(false);
        issue.setSuspensionReason("approved plan awaits operator start");
        issue.setLastFailureReason("historical diagnostic");
        issue.setStatus(IssueStatus.READY_TO_START);
        issues.saveAndFlush(issue);

        TrackedIssue suspended = checkpoints.suspendForRecovery(issue.getId());

        assertThat(suspended.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(suspended.getApprovedPlanningVersion().getId()).isEqualTo(approved.getId());
        assertThat(suspended.getCurrentIteration()).isEqualTo(2);
        assertThat(suspended.getCurrentPhase()).isEqualTo("IMPLEMENTATION");
        assertThat(suspended.getPlanConformanceAttempt()).isEqualTo(1);
        assertThat(suspended.isPlanCorrectionPending()).isFalse();
        assertThat(suspended.getSuspensionReason())
                .isEqualTo("approved plan awaits operator start");
        assertThat(suspended.getLastFailureReason()).isEqualTo("historical diagnostic");
    }

    private Baseline seed() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "checkpoint-" + System.nanoTime()));
        TrackedIssue issue = new TrackedIssue(repo, 42, "Durable checkpoint");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(1);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue = issues.saveAndFlush(issue);
        Iteration iteration = new Iteration(issue, 1);
        iteration.setImplModel("gpt-5.6-sol");
        iteration = iterations.saveAndFlush(iteration);
        return new Baseline(issue.getId(), iteration.getId());
    }

    private CheckpointRaceSeed seedCheckpointRace() {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo(
                "acme", "checkpoint-race-" + System.nanoTime()));
        TrackedIssue owner = new TrackedIssue(repo, 41, "Plan approval owner");
        owner.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        owner = issues.saveAndFlush(owner);
        PlanningVersion ownerVersion = versions.saveAndFlush(PlanningVersion.pending(
                owner, 1, "owner design", "owner plan", "CODEX", "gpt-5.6-sol", null));

        TrackedIssue implementation = new TrackedIssue(repo, 42, "Implementation checkpoint");
        implementation.setStatus(IssueStatus.IN_PROGRESS);
        implementation.setCurrentIteration(1);
        implementation.setCurrentPhase("IMPLEMENTATION");
        implementation = issues.saveAndFlush(implementation);
        Iteration iteration = new Iteration(implementation, 1);
        iteration.setImplModel("gpt-5.6-sol");
        iteration = iterations.saveAndFlush(iteration);
        return new CheckpointRaceSeed(
                repo.getId(), owner.getId(), ownerVersion.getId(),
                implementation.getId(), iteration.getId());
    }

    private CheckpointRace raceApprovalAgainstCheckpoint(CheckpointRaceSeed seed,
                                                          CheckpointOperation operation)
            throws Exception {
        CountDownLatch approvalHasOrderedIssueLocks = new CountDownLatch(1);
        CountDownLatch releaseApproval = new CountDownLatch(1);
        CountDownLatch checkpointRequestsRepositoryLock = new CountDownLatch(1);
        AtomicInteger repositoryLockRequests = new AtomicInteger();
        doAnswer(invocation -> {
            if (repositoryLockRequests.incrementAndGet() > 1) {
                checkpointRequestsRepositoryLock.countDown();
            }
            return entityManager.createQuery(
                            "select repo from WatchedRepo repo where repo.id = :repoId",
                            WatchedRepo.class)
                    .setParameter("repoId", seed.repoId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream()
                    .findFirst();
        }).when(repos).findByIdForUpdate(seed.repoId());
        doAnswer(invocation -> {
            List<TrackedIssue> locked = entityManager.createQuery(
                            "select distinct issue from TrackedIssue issue "
                                    + "left join fetch issue.approvedPlanningVersion "
                                    + "join fetch issue.repo "
                                    + "where issue.repo.id = :repoId "
                                    + "order by issue.issueNumber",
                            TrackedIssue.class)
                    .setParameter("repoId", seed.repoId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            approvalHasOrderedIssueLocks.countDown();
            assertThat(releaseApproval.await(5, TimeUnit.SECONDS)).isTrue();
            return locked;
        }).when(issues).findByRepoIdForUpdateOrderByIssueNumber(seed.repoId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RaceAttempt<PlanFirstTransactionManager.LifecycleCommit>> approval =
                    executor.submit(() -> attempt(() -> planTransactions.approvePlan(
                            seed.ownerIssueId(), seed.ownerVersionId())));
            assertThat(approvalHasOrderedIssueLocks.await(5, TimeUnit.SECONDS)).isTrue();
            Future<RaceAttempt<WorkflowCheckpointTransactionManager.ImplementationCheckpoint>>
                    checkpoint = executor.submit(() -> attempt(operation::run));
            // The old checkpoint order goes straight to the joined issue lock and never reaches
            // this repository-mutex assertion while approval holds the ordered issue set.
            assertThat(checkpointRequestsRepositoryLock.await(5, TimeUnit.SECONDS)).isTrue();
            releaseApproval.countDown();
            return new CheckpointRace(
                    approval.get(10, TimeUnit.SECONDS),
                    checkpoint.get(10, TimeUnit.SECONDS));
        } finally {
            releaseApproval.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> RaceAttempt<T> attempt(CheckpointOperationWithResult<T> operation) {
        try {
            return new RaceAttempt<>(operation.run(), null);
        } catch (RuntimeException failure) {
            return new RaceAttempt<>(null, failure);
        }
    }

    private static void assertNoSerializationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isNotEqualTo("40001");
            }
        }
    }

    private record Baseline(Long issueId, Long iterationId) { }

    @FunctionalInterface
    private interface CheckpointOperation {
        WorkflowCheckpointTransactionManager.ImplementationCheckpoint run();
    }

    @FunctionalInterface
    private interface CheckpointOperationWithResult<T> {
        T run();
    }

    private record CheckpointRaceSeed(Long repoId,
                                      Long ownerIssueId,
                                      Long ownerVersionId,
                                      Long implementationIssueId,
                                      Long iterationId) { }

    private record RaceAttempt<T>(T value, RuntimeException failure) { }

    private record CheckpointRace(
            RaceAttempt<PlanFirstTransactionManager.LifecycleCommit> approval,
            RaceAttempt<WorkflowCheckpointTransactionManager.ImplementationCheckpoint> checkpoint) { }
}
