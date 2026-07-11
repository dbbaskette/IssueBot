package com.dbbaskette.issuebot.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
    void effectivePlanFirst_falseByDefault() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertEquals(false, issue.effectivePlanFirst());
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
}
