package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrphanedRunRecoveryTest {

    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private PlanningVersionRepository versionRepository;
    private EventService eventService;
    private OrphanedRunRecovery recovery;
    private WatchedRepo repo;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        versionRepository = mock(PlanningVersionRepository.class);
        eventService = mock(EventService.class);
        recovery = new OrphanedRunRecovery(
                issueRepository, iterationRepository, versionRepository, eventService);
        repo = new WatchedRepo("owner", "repo");
    }

    @Test
    void requeuesInterruptedPlanningFromPersistedSetupPhaseWithoutCurrentVersionOnce() {
        TrackedIssue orphan = new TrackedIssue(repo, 96, "Sub-task");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("SETUP");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS))
                .thenReturn(List.of(orphan), List.of());
        when(versionRepository.findFirstByIssueIdOrderByVersionNumberDesc(orphan.getId()))
                .thenReturn(Optional.empty());

        recovery.requeueOrphanedRuns();
        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verify(eventService).log(eq("ISSUE_RECOVERED"), anyString(), eq(repo), eq(orphan));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void nativeInputWaitIsNeverRequeuedOrChargedAnotherAttemptAfterRestart() {
        var waiting=new TrackedIssue(repo,99,"Waiting input");
        waiting.setStatus(IssueStatus.IN_PROGRESS);waiting.setCurrentPhase("IMPLEMENTATION");
        waiting.setCurrentIteration(2);waiting.setWaitingForInput(true);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(waiting));
        recovery.requeueOrphanedRuns();
        assertEquals(IssueStatus.IN_PROGRESS,waiting.getStatus());assertEquals(2,waiting.getCurrentIteration());
        verify(issueRepository,never()).save(any());
        verifyNoInteractions(iterationRepository,versionRepository,eventService);
    }

    @Test
    void interruptedPlanningWithPendingVersionReturnsToAwaitingApprovalWithoutDuplication() {
        TrackedIssue orphan = new TrackedIssue(repo, 96, "Sub-task");
        orphan.setId(96L);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("SETUP");
        PlanningVersion pending = PlanningVersion.pending(
                orphan, 3, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(versionRepository.findFirstByIssueIdOrderByVersionNumberDesc(96L))
                .thenReturn(Optional.of(pending));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.AWAITING_PLAN_APPROVAL, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verify(versionRepository, never()).save(any());
        verify(eventService).log(eq("ISSUE_RECOVERED"), anyString(), eq(repo), eq(orphan));
    }

    @Test
    void requeuesInterruptedCorrectionWithoutResettingApprovedContractOrAttempt() {
        TrackedIssue orphan = new TrackedIssue(repo, 97, "Corrective pass");
        orphan.setId(97L);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("INDEPENDENT_REVIEW");
        orphan.setPlanConformanceAttempt(1);
        orphan.setPlanCorrectionPending(true);
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 2, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        assertEquals(1, orphan.getPlanConformanceAttempt());
        assertEquals(true, orphan.isPlanCorrectionPending());
        assertEquals(approved, orphan.getApprovedPlanningVersion());
        verify(issueRepository).save(orphan);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void restoresEligibilityWhenCorrectionIterationWasClaimedBeforeRestart() {
        TrackedIssue orphan = new TrackedIssue(repo, 101, "Claimed corrective pass");
        orphan.setId(101L);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentIteration(2);
        orphan.setCurrentPhase("IMPLEMENTATION");
        orphan.setPlanConformanceAttempt(1);
        orphan.setPlanCorrectionPending(false);
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 2, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        Iteration unexecutedClaim = new Iteration(orphan, 2);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(101L, 2))
                .thenReturn(Optional.of(unexecutedClaim));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(1, orphan.getCurrentIteration());
        assertEquals(1, orphan.getPlanConformanceAttempt());
        assertEquals(true, orphan.isPlanCorrectionPending());
        assertEquals(approved, orphan.getApprovedPlanningVersion());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void interruptedGuidedImplementationRearmsItsDurablePromptIteration() {
        TrackedIssue orphan = new TrackedIssue(repo, 103, "Guided implementation");
        orphan.setId(103L);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentIteration(1);
        orphan.setCurrentPhase("IMPLEMENTATION");
        orphan.setPlanConformanceAttempt(0);
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 2, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        Iteration interrupted = new Iteration(orphan, 1);
        interrupted.setImplementationContext(
                "ADDITIONAL HUMAN GUIDANCE: preserve the rollback contract");
        interrupted.setImplementationContextPrepared(true);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(103L, 1))
                .thenReturn(Optional.of(interrupted));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(0, orphan.getCurrentIteration());
        assertEquals(false, orphan.isPlanCorrectionPending());
        assertNull(orphan.getCurrentPhase());
        assertEquals("ADDITIONAL HUMAN GUIDANCE: preserve the rollback contract",
                interrupted.getImplementationContext());
    }

    @Test
    void interruptedHarnessTurnKeepsSessionBranchAndIterationForResume() {
        TrackedIssue orphan = new TrackedIssue(repo, 104, "Native coding loop");
        orphan.setId(104L);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentIteration(1);
        orphan.setCurrentPhase("IMPLEMENTATION");
        orphan.setBranchName("issuebot/issue-104-native");
        orphan.setClaudeSessionId("native-session");
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 1, "spec", "plan", "CODEX", "gpt-6-astra", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        Iteration partial = new Iteration(orphan, 1);
        partial.setImplementationTurnCount(1);
        partial.setClaudeSessionId("native-session");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(104L, 1))
                .thenReturn(Optional.of(partial));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(1, orphan.getCurrentIteration());
        assertEquals("IMPLEMENTATION", orphan.getCurrentPhase());
        assertEquals("issuebot/issue-104-native", orphan.getBranchName());
        assertEquals("native-session", orphan.getClaudeSessionId());
    }

    @Test
    void completedCorrectionImplementationIsNotRearmedWhenTerminalHandlingWasInterrupted() {
        TrackedIssue orphan = claimedCorrection(102, "IMPLEMENTATION");
        Iteration completedClaim = new Iteration(orphan, 2);
        completedClaim.setCompletedAt(java.time.LocalDateTime.now());
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2)).thenReturn(Optional.of(completedClaim));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(2, orphan.getCurrentIteration());
        assertEquals(1, orphan.getPlanConformanceAttempt());
        assertEquals(false, orphan.isPlanCorrectionPending());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void postImplementationCorrectionPhasesKeepTheirResumeCheckpoint() {
        for (String phase : List.of(
                "LOCAL_CHECKS", "CI_VERIFICATION", "PR_CREATION",
                "INDEPENDENT_REVIEW", "COMPLETION")) {
            TrackedIssue orphan = claimedCorrection(200 + phase.length(), phase);
            if ("COMPLETION".equals(phase)) {
                orphan.setPlanConformanceAttempt(2);
            }
            Iteration claimed = new Iteration(orphan, 2);
            claimed.setCompletedAt(java.time.LocalDateTime.now());
            when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS))
                    .thenReturn(List.of(orphan));
            when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                    orphan.getId(), 2)).thenReturn(Optional.of(claimed));

            recovery.requeueOrphanedRuns();

            assertEquals(IssueStatus.PENDING, orphan.getStatus(), phase);
            assertEquals(2, orphan.getCurrentIteration(), phase);
            assertEquals("COMPLETION".equals(phase) ? 2 : 1,
                    orphan.getPlanConformanceAttempt(), phase);
            assertEquals(false, orphan.isPlanCorrectionPending(), phase);
            assertEquals(phase, orphan.getCurrentPhase(), phase);
        }
        verify(versionRepository, never()).save(any());
    }

    @Test
    void persistedSecondReviewVerdictKeepsReviewCheckpointWithoutRearmingCorrection() {
        TrackedIssue orphan = claimedCorrection(219, "INDEPENDENT_REVIEW");
        orphan.setPlanConformanceAttempt(2);
        Iteration reviewed = new Iteration(orphan, 2);
        reviewed.setCompletedAt(java.time.LocalDateTime.now());
        reviewed.setReviewPassed(true);
        reviewed.setReviewJson("{\"passed\":true}");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2)).thenReturn(Optional.of(reviewed));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals("INDEPENDENT_REVIEW", orphan.getCurrentPhase());
        assertEquals(2, orphan.getCurrentIteration());
        assertEquals(2, orphan.getPlanConformanceAttempt());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    @Test
    void firstReviewInvocationFailureKeepsCheckpointWithZeroConformanceAttempts() {
        TrackedIssue orphan = claimedCorrection(218, "INDEPENDENT_REVIEW");
        orphan.setCurrentIteration(1);
        orphan.setPlanConformanceAttempt(0);
        Iteration reviewed = new Iteration(orphan, 1);
        reviewed.setCompletedAt(java.time.LocalDateTime.now());
        reviewed.setReviewPassed(false);
        reviewed.setReviewJson(null);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 1)).thenReturn(Optional.of(reviewed));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals("INDEPENDENT_REVIEW", orphan.getCurrentPhase());
        assertEquals(1, orphan.getCurrentIteration());
        assertEquals(0, orphan.getPlanConformanceAttempt());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    @Test
    void duplicateIterationNumbersUseNewestStableIdForCurrentGuidedRetry() {
        TrackedIssue orphan = claimedCorrection(220, "IMPLEMENTATION");
        Iteration priorRun = new Iteration(orphan, 2);
        priorRun.setId(10L);
        priorRun.setCompletedAt(java.time.LocalDateTime.now());
        priorRun.setDiff("stale prior-run diff");
        Iteration currentRun = new Iteration(orphan, 2);
        currentRun.setId(20L);
        currentRun.setDiff("current guided-retry diff");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2)).thenReturn(Optional.of(currentRun));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(1, orphan.getCurrentIteration());
        assertEquals(true, orphan.isPlanCorrectionPending());
        verify(iterationRepository).findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2);
    }

    @Test
    void persistedLocalFailureBelowBudgetReturnsToOrdinaryNextIterationSemantics() {
        TrackedIssue orphan = claimedCorrection(221, "LOCAL_CHECKS");
        orphan.setCurrentIteration(1);
        Iteration failed = new Iteration(orphan, 1);
        failed.setId(21L);
        failed.setLocalCheckResult("FAILED");
        failed.setCompletedAt(java.time.LocalDateTime.now());
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 1)).thenReturn(Optional.of(failed));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(1, orphan.getCurrentIteration());
        assertNull(orphan.getCurrentPhase());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    @Test
    void persistedCiFailureAtMaxBudgetReturnsToOrdinaryTerminalSemantics() {
        TrackedIssue orphan = claimedCorrection(222, "CI_VERIFICATION");
        Iteration failed = new Iteration(orphan, 2);
        failed.setId(22L);
        failed.setCiResult("FAILED");
        failed.setCompletedAt(java.time.LocalDateTime.now());
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2)).thenReturn(Optional.of(failed));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(2, orphan.getCurrentIteration());
        assertNull(orphan.getCurrentPhase());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    @Test
    void persistedLocalFailureAtMaxBudgetReturnsToOrdinaryTerminalSemantics() {
        TrackedIssue orphan = claimedCorrection(223, "LOCAL_CHECKS");
        Iteration failed = new Iteration(orphan, 2);
        failed.setId(23L);
        failed.setLocalCheckResult("FAILED");
        failed.setCompletedAt(java.time.LocalDateTime.now());
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 2)).thenReturn(Optional.of(failed));

        recovery.requeueOrphanedRuns();

        assertEquals(2, orphan.getCurrentIteration());
        assertNull(orphan.getCurrentPhase());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    @Test
    void persistedCiFailureBelowBudgetReturnsToOrdinaryNextIterationSemantics() {
        TrackedIssue orphan = claimedCorrection(224, "CI_VERIFICATION");
        orphan.setCurrentIteration(1);
        Iteration failed = new Iteration(orphan, 1);
        failed.setId(24L);
        failed.setCiResult("FAILED");
        failed.setCompletedAt(java.time.LocalDateTime.now());
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                orphan.getId(), 1)).thenReturn(Optional.of(failed));

        recovery.requeueOrphanedRuns();

        assertEquals(1, orphan.getCurrentIteration());
        assertNull(orphan.getCurrentPhase());
        assertEquals(false, orphan.isPlanCorrectionPending());
    }

    private TrackedIssue claimedCorrection(int issueNumber, String phase) {
        TrackedIssue orphan = new TrackedIssue(repo, issueNumber, "Claimed corrective pass");
        orphan.setId((long) issueNumber);
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentIteration(2);
        orphan.setCurrentPhase(phase);
        orphan.setPlanConformanceAttempt(1);
        orphan.setPlanCorrectionPending(false);
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 2, "spec", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        return orphan;
    }

    @Test
    void explicitPlanFirstOptOutPreservesLegacyInProgressRecovery() {
        repo.setPlanFirst(false);
        TrackedIssue orphan = new TrackedIssue(repo, 98, "Ordinary implementation");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("IMPLEMENTATION");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verifyNoInteractions(versionRepository);
    }

    @Test
    void approvedPlanFirstRunPreservesApprovedVersionDuringLegacyRecovery() {
        TrackedIssue orphan = new TrackedIssue(repo, 99, "Approved implementation");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("CI_VERIFICATION");
        orphan.setPlanConformanceAttempt(1);
        PlanningVersion approved = PlanningVersion.pending(
                orphan, 4, "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        orphan.setApprovedPlanningVersion(approved);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        assertEquals(1, orphan.getPlanConformanceAttempt());
        assertEquals(approved, orphan.getApprovedPlanningVersion());
        assertEquals("approved spec", approved.getDesignSpec());
        assertEquals("approved plan", approved.getImplementationPlan());
        verify(issueRepository).save(orphan);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void invalidUnapprovedPlanFirstImplementationIsLeftUntouched() {
        TrackedIssue orphan = new TrackedIssue(repo, 100, "Invalid implementation");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("IMPLEMENTATION");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.IN_PROGRESS, orphan.getStatus());
        assertEquals("IMPLEMENTATION", orphan.getCurrentPhase());
        verify(issueRepository, never()).save(any());
        verifyNoInteractions(versionRepository, eventService);
    }

    @Test
    void noOrphans_isNoOp() {
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of());

        recovery.requeueOrphanedRuns();

        verify(issueRepository, never()).save(any());
        verifyNoInteractions(eventService);
    }

    @Test
    void onlyTouchesInProgress_humanWaitStatesSurviveUntouched() {
        // A human-wait issue that co-exists in the DB must NOT be reset — its pending decision
        // would be lost. Recovery is scoped by the query (IN_PROGRESS only), never fetch-all-filter.
        TrackedIssue awaitingApproval = new TrackedIssue(repo, 50, "Waiting on human");
        awaitingApproval.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        TrackedIssue failed = new TrackedIssue(repo, 51, "Needs guidance");
        failed.setStatus(IssueStatus.FAILED);
        failed.setPlanConformanceAttempt(2);
        TrackedIssue cooldown = new TrackedIssue(repo, 52, "Cooling down");
        cooldown.setStatus(IssueStatus.COOLDOWN);
        cooldown.setPlanConformanceAttempt(2);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of());

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.AWAITING_PLAN_APPROVAL, awaitingApproval.getStatus());
        assertEquals(IssueStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getPlanConformanceAttempt());
        assertEquals(IssueStatus.COOLDOWN, cooldown.getStatus());
        assertEquals(2, cooldown.getPlanConformanceAttempt());
        verify(issueRepository, never()).save(awaitingApproval);
        verify(issueRepository, never()).save(failed);
        verify(issueRepository, never()).save(cooldown);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_APPROVAL);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_DECOMPOSITION);
        verify(issueRepository, never()).findByStatus(IssueStatus.FAILED);
        verify(issueRepository, never()).findByStatus(IssueStatus.COOLDOWN);
        verifyNoInteractions(versionRepository, eventService);
    }

    @Test
    void staleRecoveryResponseCannotChangeReadyReservation() {
        TrackedIssue ready = new TrackedIssue(repo, 53, "Approved and waiting");
        ready.setId(53L);
        ready.setStatus(IssueStatus.READY_TO_START);
        ready.setCurrentIteration(2);
        ready.setCurrentPhase("HUMAN_START_GATE");
        PlanningVersion approved = PlanningVersion.pending(
                ready, 2, "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(java.time.LocalDateTime.now());
        ready.setApprovedPlanningVersion(approved);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(ready));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.READY_TO_START, ready.getStatus());
        assertEquals(approved, ready.getApprovedPlanningVersion());
        assertEquals(2, ready.getCurrentIteration());
        assertEquals("HUMAN_START_GATE", ready.getCurrentPhase());
        verify(issueRepository, never()).save(ready);
        verifyNoInteractions(iterationRepository, versionRepository, eventService);
    }
}
