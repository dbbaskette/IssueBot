package com.dbbaskette.issuebot.model;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Budget precedence (#66) is a pure derivation on the entity —
 * {@link TrackedIssue#effectiveBudgetUsd()} is the single source of truth used by
 * both the workflow's overBudget checkpoint and the issue-detail view.
 */
class TrackedIssueTest {

    @Test
    void effectiveBudgetUsd_nullWhenNeitherSet() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertNull(issue.effectiveBudgetUsd());
    }

    @Test
    void effectiveBudgetUsd_usesRepoWhenNoOverride() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setIssueBudgetUsd(new BigDecimal("5.00"));
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertEquals(0, new BigDecimal("5.00").compareTo(issue.effectiveBudgetUsd()));
    }

    @Test
    void effectiveBudgetUsd_issueOverrideWinsOverRepo() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setIssueBudgetUsd(new BigDecimal("5.00"));
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setBudgetOverrideUsd(new BigDecimal("1.00"));

        assertEquals(0, new BigDecimal("1.00").compareTo(issue.effectiveBudgetUsd()));
    }

    /**
     * Plan-first precedence (#64) mirrors budget precedence: the per-issue override,
     * when set, wins over the repo default; when unset (null), the repo default applies.
     */
    @Test
    void effectivePlanFirst_usesRepoWhenNoOverride() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setPlanFirst(true);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertEquals(true, issue.effectivePlanFirst());
    }

    @Test
    void effectivePlanFirst_trueByDefault() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertEquals(true, issue.effectivePlanFirst());
    }

    @Test
    void effectivePlanFirst_issueOverrideWinsOverRepo_trueOverridesFalse() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setPlanFirst(false);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setPlanFirstOverride(true);

        assertEquals(true, issue.effectivePlanFirst());
    }

    @Test
    void effectivePlanFirst_issueOverrideWinsOverRepo_falseOverridesTrue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setPlanFirst(true);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setPlanFirstOverride(false);

        assertEquals(false, issue.effectivePlanFirst());
    }

    @Test
    void cleanResetClearsWorkflowDataButPreservesOrderingMetadataAndOverrides() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 142, "Preserve this title");
        issue.setId(17L);
        issue.setStatus(IssueStatus.READY_TO_START);
        issue.setCurrentIteration(3);
        issue.setBranchName("issuebot/issue-142");
        issue.setCooldownUntil(LocalDateTime.of(2026, 7, 23, 8, 0));
        issue.setStartedAt(LocalDateTime.of(2026, 7, 22, 8, 0));
        issue.setCurrentPhase("PLANNING");
        issue.setCurrentReviewIteration(2);
        issue.setPrNumber(99);
        issue.setResolvedImplModel("gpt-5.6-sol");
        issue.setResolvedReviewModel("gpt-5.6-terra");
        issue.setResolvedAgentProvider(AgentProvider.CODEX);
        issue.setLastFailureReason("prior failure");
        issue.setSuspensionReason("operator hold");
        issue.setClaudeSessionId("session-142");
        issue.setImplementationPlan("# Implementation Plan");
        issue.setPlanApproved(true);
        issue.setPlanRejections(2);
        issue.setPlanFeedback("please revise");
        issue.setApprovedPlanningVersion(PlanningVersion.pending(
                issue, 1, "# Design Spec", "# Implementation Plan", "codex", "gpt-5.6-sol", null));
        issue.setPlanConformanceAttempt(2);
        issue.setPlanCorrectionPending(true);
        issue.setBlockedByIssues("141");
        issue.setImplModelOverride("gpt-5.6-sol");
        issue.setReviewModelOverride("gpt-5.6-terra");
        issue.setPlanFirstOverride(false);
        issue.setBudgetOverrideUsd(new BigDecimal("12.50"));
        issue.setDecompositionProposal("split this later");
        LocalDateTime createdAt = issue.getCreatedAt();

        issue.resetPlanningStateToQueued();

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(issue.getCurrentIteration()).isZero();
        assertThat(issue.getBranchName()).isNull();
        assertThat(issue.getCooldownUntil()).isNull();
        assertThat(issue.getStartedAt()).isNull();
        assertThat(issue.getCurrentPhase()).isNull();
        assertThat(issue.getCurrentReviewIteration()).isZero();
        assertThat(issue.getPrNumber()).isNull();
        assertThat(issue.getResolvedImplModel()).isNull();
        assertThat(issue.getResolvedReviewModel()).isNull();
        assertThat(issue.getResolvedAgentProvider()).isNull();
        assertThat(issue.getLastFailureReason()).isNull();
        assertThat(issue.getSuspensionReason()).isNull();
        assertThat(issue.getClaudeSessionId()).isNull();
        assertThat(issue.getImplementationPlan()).isNull();
        assertThat(issue.isPlanApproved()).isFalse();
        assertThat(issue.getPlanRejections()).isZero();
        assertThat(issue.getPlanFeedback()).isNull();
        assertThat(issue.getApprovedPlanningVersion()).isNull();
        assertThat(issue.getPlanConformanceAttempt()).isZero();
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        assertThat(issue.getId()).isEqualTo(17L);
        assertThat(issue.getRepo()).isSameAs(repo);
        assertThat(issue.getIssueNumber()).isEqualTo(142);
        assertThat(issue.getIssueTitle()).isEqualTo("Preserve this title");
        assertThat(issue.getBlockedByIssues()).isEqualTo("141");
        assertThat(issue.getImplModelOverride()).isEqualTo("gpt-5.6-sol");
        assertThat(issue.getReviewModelOverride()).isEqualTo("gpt-5.6-terra");
        assertThat(issue.getPlanFirstOverride()).isFalse();
        assertThat(issue.getBudgetOverrideUsd()).isEqualByComparingTo("12.50");
        assertThat(issue.getCreatedAt()).isEqualTo(createdAt);
        assertThat(issue.getDecompositionProposal()).isEqualTo("split this later");
    }
}
