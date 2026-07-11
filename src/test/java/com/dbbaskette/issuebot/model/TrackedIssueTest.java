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
}
