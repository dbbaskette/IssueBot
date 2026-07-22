package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@DataJpaTest
@Import(WorkflowCheckpointTransactionManager.class)
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowCheckpointTransactionManagerTest {

    @Autowired private WorkflowCheckpointTransactionManager checkpoints;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private PlanningVersionRepository versions;
    @MockitoSpyBean private IterationRepository iterations;
    @MockitoSpyBean private IssueGuidanceRepository guidance;

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(issue.getId());

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(baseline.issueId());

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(issue.getId());

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(issue.getId());

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(issue.getId());

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

        TrackedIssue suspended = checkpoints.suspendForGlobalPause(issue.getId());

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

    private record Baseline(Long issueId, Long iterationId) { }
}
